package com.cardtalk.billing;

import java.time.Instant;
import java.util.Map;

/** Implementations authenticate all provider replies; redirects are never payment evidence. */
public interface PaymentProvider {
    String name();
    boolean configured();
    String merchantId();
    String appId();
    record CheckoutRequest(String orderId,String outTradeNo,String description,long amountMinor,String currency,String payerClientIp,String notifyUrl) {}
    record Checkout(String checkoutUrl,String providerReference) {}
    record RefundRequest(String outTradeNo,String outRefundNo,long totalMinor,long refundMinor,String currency,String reason) {}
    record RefundResult(String outRefundNo,String providerRefundId,String status) {}
    enum EventType { PAID,CLOSED,FAILED,REFUNDED,REFUND_FAILED }
    record ProviderEvent(String eventId,EventType type,String outTradeNo,String outRefundNo,String transactionId,long amountMinor,long refundAmountMinor,String currency,String merchantId,String appId,Instant occurredAt) {}
    Checkout createCheckout(CheckoutRequest request);
    ProviderEvent queryPayment(String outTradeNo);
    void close(String outTradeNo);
    RefundResult refund(RefundRequest request);
    ProviderEvent queryRefund(String outRefundNo);
    ProviderEvent verifyNotification(Map<String,String> headers,byte[] rawBody);
}
