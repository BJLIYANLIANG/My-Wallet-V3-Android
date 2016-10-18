package piuk.blockchain.android.ui.account;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import info.blockchain.api.Unspent;
import info.blockchain.wallet.multiaddr.MultiAddrFactory;
import info.blockchain.wallet.payload.LegacyAddress;
import info.blockchain.wallet.payload.PayloadManager;
import info.blockchain.wallet.payment.Payment;
import info.blockchain.wallet.payment.data.SweepBundle;
import info.blockchain.wallet.payment.data.UnspentOutputs;
import info.blockchain.wallet.send.SendCoins;
import info.blockchain.wallet.util.CharSequenceX;

import org.bitcoinj.core.ECKey;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import piuk.blockchain.android.R;
import piuk.blockchain.android.data.cache.DynamicFeeCache;
import piuk.blockchain.android.data.payload.PayloadBridge;
import piuk.blockchain.android.ui.base.BaseViewModel;
import piuk.blockchain.android.ui.send.PendingTransaction;
import piuk.blockchain.android.util.PrefsUtil;
import piuk.blockchain.android.util.annotations.Thunk;

@SuppressWarnings("WeakerAccess")
public class AccountViewModel extends BaseViewModel {

    private final String TAG = getClass().getSimpleName();

    private Context context;
    @Thunk DataListener dataListener;

    private PayloadManager payloadManager;
    private PrefsUtil prefsUtil;

    public AccountViewModel(Context context, DataListener dataListener) {
        this.context = context;
        this.dataListener = dataListener;
        this.payloadManager = PayloadManager.getInstance();
        this.prefsUtil = new PrefsUtil(context);
    }

    public interface DataListener {
        void onShowTransferableLegacyFundsWarning(boolean isAutoPopup, ArrayList<PendingTransaction> pendingTransactionList, long totalBalance, long totalFee);

        void onSetTransferLegacyFundsMenuItemVisible(boolean visible);

        void onShowProgressDialog(String title, String message);

        void onShowTransactionSuccess(ArrayList<PendingTransaction> pendingSpendList);

        void onDismissProgressDialog();

        void onUpdateAccountsList();
    }

    @Override
    public void onViewReady() {
        // No-op
    }

    @Override
    public void destroy() {
        super.destroy();
        context = null;
        dataListener = null;
    }

    /**
     * Silently check if there are any spendable legacy funds that need to be sent to default
     * account. Prompt user when done calculating.
     */
    public void checkTransferableLegacyFunds(boolean isAutoPopup) {

        new AsyncTask<Void, Void, Void>() {

            long totalToSend = 0;
            long totalFee = 0;
            ArrayList<PendingTransaction> pendingTransactionList = null;

            @Override
            protected Void doInBackground(Void... voids) {

                Payment payment = new Payment();
                BigInteger suggestedFeePerKb = DynamicFeeCache.getInstance().getSuggestedFee().defaultFeePerKb;
                pendingTransactionList = new ArrayList<>();

                int defaultIndex = payloadManager.getPayload().getHdWallet().getDefaultIndex();

                List<LegacyAddress> legacyAddresses = payloadManager.getPayload().getLegacyAddresses();
                for (LegacyAddress legacyAddress : legacyAddresses) {

                    if (!legacyAddress.isWatchOnly() && MultiAddrFactory.getInstance().getLegacyBalance(legacyAddress.getAddress()) > 0) {
                        try {

                            JSONObject unspentResponse = new Unspent().getUnspentOutputs(legacyAddress.getAddress());
                            if (unspentResponse != null) {
                                UnspentOutputs coins = payment.getCoins(unspentResponse);
                                SweepBundle sweepBundle = payment.getSweepBundle(coins, suggestedFeePerKb);

                                //Don't sweep if there are still unconfirmed funds in address
                                if (coins.getNotice() == null && sweepBundle.getSweepAmount().compareTo(SendCoins.bDust) == 1) {
                                    final PendingTransaction pendingSpend = new PendingTransaction();
                                    pendingSpend.unspentOutputBundle = payment.getSpendableCoins(coins, sweepBundle.getSweepAmount(), suggestedFeePerKb);
                                    pendingSpend.sendingObject = new ItemAccount(legacyAddress.getLabel(), "", "", legacyAddress);
                                    pendingSpend.bigIntFee = pendingSpend.unspentOutputBundle.getAbsoluteFee();
                                    pendingSpend.bigIntAmount = sweepBundle.getSweepAmount();
                                    pendingSpend.addressToReceiveIndex = defaultIndex;
                                    totalToSend += pendingSpend.bigIntAmount.longValue();
                                    totalFee += pendingSpend.bigIntFee.longValue();
                                    pendingTransactionList.add(pendingSpend);
                                }
                            }

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }

                return null;
            }

            @Override
            protected void onPostExecute(Void voids) {
                super.onPostExecute(voids);
                dataListener.onDismissProgressDialog();

                if (payloadManager.getPayload().isUpgraded() && pendingTransactionList.size() > 0) {
                    dataListener.onSetTransferLegacyFundsMenuItemVisible(true);

                    if (prefsUtil.getValue("WARN_TRANSFER_ALL", true) || !isAutoPopup) {
                        dataListener.onShowTransferableLegacyFundsWarning(isAutoPopup, pendingTransactionList, totalToSend, totalFee);
                    }

                } else {
                    dataListener.onSetTransferLegacyFundsMenuItemVisible(false);
                }
            }

        }.execute();
    }

    public void sendPayment(final ArrayList<PendingTransaction> pendingSpendList, CharSequenceX secondPassword) {

        new AsyncTask<Void, Void, Void>() {

            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                dataListener.onShowProgressDialog(context.getString(R.string.app_name), context.getString(R.string.please_wait));
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                super.onPostExecute(aVoid);
                dataListener.onDismissProgressDialog();
            }

            @Override
            protected Void doInBackground(Void... voids) {

                for (int i = 0; i < pendingSpendList.size(); i++) {

                    PendingTransaction pendingTransaction = pendingSpendList.get(i);

                    try {

                        LegacyAddress legacyAddress = ((LegacyAddress) pendingTransaction.sendingObject.accountObject);
                        String changeAddress = legacyAddress.getAddress();
                        String receivingAddress = payloadManager.getNextReceiveAddress(pendingTransaction.addressToReceiveIndex);

                        List<ECKey> keys = new ArrayList<>();
                        if (payloadManager.getPayload().isDoubleEncrypted()) {
                            ECKey walletKey = legacyAddress.getECKey(secondPassword);
                            keys.add(walletKey);
                        } else {
                            ECKey walletKey = legacyAddress.getECKey();
                            keys.add(walletKey);
                        }

                        final int finalI = i;
                        new Payment().submitPayment(
                                pendingTransaction.unspentOutputBundle,
                                keys,
                                receivingAddress,
                                changeAddress,
                                pendingTransaction.bigIntFee,
                                pendingTransaction.bigIntAmount,
                                new Payment.SubmitPaymentListener() {
                                    @Override
                                    public void onSuccess(String s) {
                                        payloadManager.getPayload().getHdWallet().getAccounts().get(pendingTransaction.addressToReceiveIndex).incReceive();
                                        MultiAddrFactory.getInstance().setLegacyBalance(MultiAddrFactory.getInstance().getLegacyBalance() - (pendingTransaction.bigIntAmount.longValue() + pendingTransaction.bigIntFee.longValue()));

                                        if (finalI == pendingSpendList.size() - 1) {
                                            dataListener.onUpdateAccountsList();
                                            dataListener.onSetTransferLegacyFundsMenuItemVisible(false);
                                            dataListener.onShowTransactionSuccess(pendingSpendList);
                                            PayloadBridge.getInstance().remoteSaveThread(null);
                                        }
                                    }

                                    @Override
                                    public void onFail(String error) {
                                        Log.e(TAG, error);
                                    }
                                });

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                return null;
            }

        }.execute();

    }
}
