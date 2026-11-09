package com.physicalactivitygate.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.physicalactivitygate.data.AppDatabase
import com.physicalactivitygate.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// These product IDs must be created to match exactly in Play Console
// (Monetize > Subscriptions) before real purchases will work. Until then,
// queryProducts() simply won't find them and the screen falls back to
// showing your planned prices as placeholders.
const val PRO_MONTHLY_ID = "pro_monthly"
const val PRO_YEARLY_ID = "pro_yearly"

data class BillingUiState(
    val connected: Boolean = false,
    val monthlyPriceText: String? = null,
    val yearlyPriceText: String? = null,
    val purchaseInProgress: Boolean = false,
    val restoring: Boolean = false,
    val restoreMessage: String? = null,
    val lastError: String? = null
)

/**
 * Thin wrapper around Google Play Billing for the two subscription plans.
 * This app has no backend server by design (local-only, no account) — so
 * entitlement is verified client-side (Purchase.purchaseState == PURCHASED,
 * then acknowledged) and cached locally in Settings, consistent with the
 * rest of the app. That's a reasonable trade-off for this app's threat
 * model; an app handling significant revenue would additionally verify
 * purchases server-side via the Play Developer API.
 */
class BillingManager(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo = Repository(AppDatabase.get(appContext))

    private val _state = MutableStateFlow(BillingUiState())
    val state: StateFlow<BillingUiState> = _state

    private var monthlyDetails: ProductDetails? = null
    private var yearlyDetails: ProductDetails? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { handlePurchase(it) }
        } else if (billingResult.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            _state.value = _state.value.copy(lastError = billingResult.debugMessage)
        }
        _state.value = _state.value.copy(purchaseInProgress = false)
    }

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                _state.value = _state.value.copy(connected = billingResult.responseCode == BillingClient.BillingResponseCode.OK)
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProducts()
                    restorePurchases()
                }
            }
            override fun onBillingServiceDisconnected() {
                _state.value = _state.value.copy(connected = false)
            }
        })
    }

    fun end() {
        runCatching { client.endConnection() }
    }

    private fun queryProducts() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder().setProductId(PRO_MONTHLY_ID).setProductType(BillingClient.ProductType.SUBS).build(),
                    QueryProductDetailsParams.Product.newBuilder().setProductId(PRO_YEARLY_ID).setProductType(BillingClient.ProductType.SUBS).build()
                )
            )
            .build()
        client.queryProductDetailsAsync(params) { billingResult, result ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                result.productDetailsList.forEach { details ->
                    val price = details.subscriptionOfferDetails
                        ?.firstOrNull()
                        ?.pricingPhases
                        ?.pricingPhaseList
                        ?.firstOrNull()
                        ?.formattedPrice
                    when (details.productId) {
                        PRO_MONTHLY_ID -> { monthlyDetails = details; _state.value = _state.value.copy(monthlyPriceText = price) }
                        PRO_YEARLY_ID -> { yearlyDetails = details; _state.value = _state.value.copy(yearlyPriceText = price) }
                    }
                }
            }
        }
    }

    fun launchPurchase(activity: Activity, productId: String) {
        val details = if (productId == PRO_MONTHLY_ID) monthlyDetails else yearlyDetails
        if (details == null) {
            _state.value = _state.value.copy(lastError = "This plan isn't set up in Play Console yet, so it can't be purchased for real. Use the developer test toggle to try the unlocked experience.")
            return
        }
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken == null) {
            _state.value = _state.value.copy(lastError = "No subscription offer is currently available for this plan.")
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()
        _state.value = _state.value.copy(purchaseInProgress = true, lastError = null)
        client.launchBillingFlow(activity, params)
    }

    fun clearRestoreMessage() {
        _state.value = _state.value.copy(restoreMessage = null)
    }

    fun restorePurchases() {
        if (!client.isReady) {
            _state.value = _state.value.copy(restoring = false, restoreMessage = "Couldn't reach Google Play. Check your connection and try again.")
            return
        }
        _state.value = _state.value.copy(restoring = true, restoreMessage = null, lastError = null)
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        client.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                if (purchases.isEmpty()) {
                    // No active subscription on this Play account — clear any
                    // stale local Pro flag from a previous purchase that has
                    // since expired/refunded. The local dev-test override is
                    // untouched; it's independent of real billing state.
                    scope.launch {
                        val s = repo.getSettings()
                        if (s.isPro) repo.saveSettings(s.copy(isPro = false, proPlan = null, proSince = null))
                        _state.value = _state.value.copy(restoring = false, restoreMessage = "No active subscription was found for this Google Play account.")
                    }
                } else {
                    scope.launch {
                        val restored = purchases
                            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                            .filter { purchase -> purchase.products.any { it == PRO_MONTHLY_ID || it == PRO_YEARLY_ID } }
                            .any { applyPurchased(it) }
                        if (restored) {
                            _state.value = _state.value.copy(restoring = false, restoreMessage = "Purchase restored successfully.")
                        } else {
                            val s = repo.getSettings()
                            if (s.isPro) repo.saveSettings(s.copy(isPro = false, proPlan = null, proSince = null))
                            _state.value = _state.value.copy(restoring = false, restoreMessage = "No active subscription was found for this Google Play account.")
                        }
                    }
                }
            } else {
                _state.value = _state.value.copy(restoring = false, restoreMessage = "Restore failed: ${billingResult.debugMessage.ifBlank { "Google Play error" }}", lastError = billingResult.debugMessage)
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        scope.launch { applyPurchased(purchase) }
    }

    private suspend fun applyPurchased(purchase: Purchase): Boolean {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return false
        val plan = purchase.products.firstOrNull { it == PRO_MONTHLY_ID || it == PRO_YEARLY_ID } ?: return false
        val s = repo.getSettings()
        repo.saveSettings(s.copy(isPro = true, proPlan = plan, proSince = purchase.purchaseTime))
        if (!purchase.isAcknowledged) {
            val ackParams = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            client.acknowledgePurchase(ackParams) { /* local entitlement is already granted above */ }
        }
        return true
    }
}
