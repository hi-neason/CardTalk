package com.cardtalk.billing;

import com.cardtalk.api.ApiException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.*;

public final class NoPaymentProvider implements PaymentProvider {
    public String name(){return "none";}public boolean configured(){return false;}public String merchantId(){return "";}public String appId(){return "";}
    static ApiException unavailable(){return new ApiException(503,"PAYMENT_NOT_CONFIGURED","支付服务尚未配置，未创建支付或开通购买权益");}
    public Checkout createCheckout(CheckoutRequest r){throw unavailable();}
    public ProviderEvent queryPayment(String id){throw unavailable();}
    public void close(String id){throw unavailable();}
    public RefundResult refund(RefundRequest r){throw unavailable();}
    public ProviderEvent queryRefund(String id){throw unavailable();}
    public ProviderEvent verifyNotification(Map<String,String> headers,byte[] body){throw unavailable();}
    @Configuration(proxyBeanMethods=false)
    public static class Defaults {
        @Bean static BeanFactoryPostProcessor validatePaymentMode(Environment env){return factory->{String mode=env.getProperty("cardtalk.billing-provider","none");if(!java.util.Set.of("none","wechat-h5").contains(mode))throw new IllegalArgumentException("Unsupported billing provider");};}
        @Bean @ConditionalOnProperty(name="cardtalk.billing-provider",havingValue="none",matchIfMissing=true) PaymentProvider noPaymentProvider(){return new NoPaymentProvider();}
    }
}
