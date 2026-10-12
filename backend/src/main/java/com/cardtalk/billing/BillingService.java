package com.cardtalk.billing;

import com.cardtalk.api.ApiException;
import com.cardtalk.family.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.cardtalk.billing.PaymentProvider.*;
import static com.cardtalk.billing.ProductsConfig.*;

@Service
public class BillingService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(BillingService.class);
    private static final long WRITE_LOCK=0x4341524454414c4bL;
    private final FamilyService families;
    private final ProductsConfig products;
    private final PaymentProvider provider;
    private final ObjectMapper mapper;
    private final boolean configured;
    private final String url,notifyUrl;
    private final Set<String> operators;
    private final Properties properties=new Properties();
    public record Actions(boolean readExisting,boolean writeAdvanced,boolean purchase) {}
    public record Entitlement(Feature feature,boolean active,String reason,String expiresAt,List<String> sources,Actions actions) {}
    public record Entitlements(boolean configured,String workspace,String role,List<Entitlement> features) {}
    public record Order(String id,String familyId,String actorId,String productId,Feature feature,String title,long amountMinor,String currency,int durationDays,String provider,String status,String outTradeNo,String outRefundNo,String checkoutUrl,String createdAt,String updatedAt) {}
    public record OrderResult(Order order,boolean providerConfigured) {}
    private record Stored(Order view,String requestHash,String merchantId,String appId,String transactionId) {}
    public BillingService(FamilyRepository repository,FamilyService families,ProductsConfig products,PaymentProvider provider,ObjectMapper mapper,
        @Value("${cardtalk.family-repository-mode:json}")String mode,@Value("${cardtalk.family-jdbc-url:}")String url,@Value("${cardtalk.family-jdbc-user:}")String user,@Value("${cardtalk.family-jdbc-password:}")String password,
        @Value("${cardtalk.billing-operator-account-ids:}")String operatorIds,@Value("${cardtalk.billing-notify-url:}")String notifyUrl){
        this.families=families;this.products=products;this.provider=provider;this.mapper=mapper;this.url=url;this.notifyUrl=notifyUrl;configured=mode.equals("postgres")&&url.startsWith("jdbc:postgresql:");
        operators=new HashSet<>();for(String id:operatorIds.split(","))if(!id.isBlank()){if(!id.trim().matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("Invalid billing operator account id");operators.add(id.trim());}
        properties.setProperty("user",user);properties.setProperty("password",password);properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","20");properties.setProperty("options","-c statement_timeout=10000 -c lock_timeout=5000");if(configured)migrate();
    }
    private static ApiException invalid(String message){return new ApiException(400,"INVALID_BILLING_REQUEST",message);}
    private static ApiException denied(){return new ApiException(403,"BILLING_ACCESS_DENIED","没有此家庭的购买或权益管理权限");}
    private static ApiException unavailable(Throwable cause){logFailure(cause);return new ApiException(503,"BILLING_STORAGE_UNAVAILABLE","权益存储暂不可用，请稍后重试",cause);}
    private static void logFailure(Throwable cause){LOG.error("Billing storage failure type={} sqlState={}",cause.getClass().getSimpleName(),cause instanceof SQLException sql?sql.getSQLState():"n/a");}
    private static void id(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,100}"))throw invalid("编号格式不正确");}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private Connection connection()throws SQLException{return DriverManager.getConnection(url,properties);}
    private static void lock(Connection c)throws SQLException{try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(?)")){s.setLong(1,WRITE_LOCK);s.execute();}}
    private void requireStorage(){if(!configured)throw new ApiException(503,"BILLING_NOT_CONFIGURED","家庭权益持久存储尚未配置");}
    private void requirePayment(){if(!provider.configured())throw NoPaymentProvider.unavailable();}
    private void migrate(){try(var in=BillingService.class.getResourceAsStream("/db/migrations/V004__billing.sql");var c=connection()){
        if(in==null)throw new IllegalStateException();byte[] bytes=in.readAllBytes();c.setAutoCommit(false);lock(c);try(var s=c.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS ct_billing_schema_version(version integer PRIMARY KEY,checksum char(64) NOT NULL)");try(var r=s.executeQuery("SELECT version,checksum FROM ct_billing_schema_version")){if(r.next()){if(r.getInt(1)!=4||!r.getString(2).equals(sha(bytes))||r.next())throw new IllegalStateException("Unsupported billing schema");c.commit();return;}}
            for(String sql:new String(bytes,StandardCharsets.UTF_8).split(";"))if(!sql.isBlank())s.execute(sql);
        }try(var s=c.prepareStatement("INSERT INTO ct_billing_schema_version VALUES(4,?)")){s.setString(1,sha(bytes));s.executeUpdate();}c.commit();
    }catch(Exception e){logFailure(e);throw new IllegalStateException("Billing schema initialization failed; no local fallback",e);}}
    private String role(Connection c,String family,String actor,boolean manager)throws SQLException{
        try(var s=c.prepareStatement("SELECT role FROM ct_family_members WHERE family_id=? AND account_id=?")){s.setString(1,family);s.setString(2,actor);try(var r=s.executeQuery()){if(!r.next())throw denied();String role=r.getString(1);if(manager&&!Set.of("owner","admin").contains(role))throw denied();return role;}}
    }
    private String localRole(FamilyService.Identity account,String family){return families.families(account).stream().filter(f->f.id().equals(family)).findFirst().orElseThrow(BillingService::denied).role();}
    public Object catalog(FamilyService.Identity actor,String family){String role=localRole(actor,family);return Map.of("products",products.products(),"provider",provider.name(),"providerConfigured",provider.configured(),"canPurchase",configured&&provider.configured()&&Set.of("owner","admin").contains(role));}
    public Entitlements entitlements(FamilyService.Identity actor,String family){
        id(family);if(!configured){String role=localRole(actor,family);return new Entitlements(false,"family",role,Arrays.stream(Feature.values()).map(f->new Entitlement(f,false,"not-configured",null,List.of(),new Actions(true,false,false))).toList());}
        try(var c=connection()){c.setAutoCommit(false);lock(c);String role=role(c,family,actor.id(),false);var result=new ArrayList<Entitlement>();Instant now=Instant.now();
            for(Feature feature:Feature.values()){
                Instant expires=null;boolean had=false,future=false;Set<String> sources=new TreeSet<>();try(var s=c.prepareStatement("SELECT starts_at,expires_at,revoked_at,source FROM ct_billing_grants WHERE family_id=? AND feature=?")){s.setString(1,family);s.setString(2,feature.name());try(var r=s.executeQuery()){while(r.next()){had=true;if(r.getTimestamp(3)!=null)continue;Instant start=r.getTimestamp(1).toInstant(),end=r.getTimestamp(2).toInstant();if(start.isAfter(now)){future=true;continue;}if(end.isAfter(now)){sources.add(r.getString(4));if(expires==null||end.isAfter(expires))expires=end;}}}}
                boolean active=expires!=null;result.add(new Entitlement(feature,active,active?"active":future?"not-started":had?"expired-or-revoked":"not-granted",expires==null?null:expires.toString(),List.copyOf(sources),new Actions(true,active&&!role.equals("helper"),provider.configured()&&Set.of("owner","admin").contains(role)&&products.products().stream().anyMatch(p->p.feature()==feature))));
            }c.commit();return new Entitlements(true,"family",role,List.copyOf(result));
        }catch(SQLException e){throw unavailable(e);}
    }
    public void requireAdvancedWrite(FamilyService.Identity actor,String family,Feature feature){
        requireStorage();try(var c=connection()){c.setAutoCommit(false);lock(c);requireAdvancedWrite(c,actor,family,feature);c.commit();}catch(SQLException e){throw unavailable(e);}
    }
    /** The caller must hold the shared family write lock in this same SQL transaction. */
    public void requireAdvancedWrite(Connection connection,FamilyService.Identity actor,String family,Feature feature)throws SQLException{
        requireStorage();if(connection.getAutoCommit())throw new IllegalArgumentException("Entitlement write checks require a transaction");
        String role=role(connection,family,actor.id(),false);if(role.equals("helper"))throw denied();
        try(var statement=connection.prepareStatement("SELECT 1 FROM ct_billing_grants WHERE family_id=? AND feature=? AND revoked_at IS NULL AND starts_at<=clock_timestamp() AND expires_at>clock_timestamp() LIMIT 1")){
            statement.setString(1,family);statement.setString(2,feature.name());try(var rows=statement.executeQuery()){if(!rows.next())throw new ApiException(403,"FEATURE_NOT_ENTITLED","此家庭功能尚未开启或已到期，已有资料仍可查看");}
        }
    }

    private Stored read(ResultSet r)throws SQLException{return new Stored(new Order(r.getString("id"),r.getString("family_id"),r.getString("actor_id"),r.getString("product_id"),Feature.valueOf(r.getString("feature")),r.getString("title"),r.getLong("amount_minor"),r.getString("currency"),r.getInt("duration_days"),r.getString("provider"),r.getString("status"),r.getString("out_trade_no"),r.getString("out_refund_no"),r.getString("checkout_url"),r.getTimestamp("created_at").toInstant().toString(),r.getTimestamp("updated_at").toInstant().toString()),r.getString("request_sha256"),r.getString("merchant_id"),r.getString("app_id"),r.getString("transaction_id"));}
    private Stored order(Connection c,String family,String orderId)throws SQLException{try(var s=c.prepareStatement("SELECT * FROM ct_billing_orders WHERE family_id=? AND id=?")){s.setString(1,family);s.setString(2,orderId);try(var r=s.executeQuery()){if(!r.next())throw new ApiException(404,"ORDER_NOT_FOUND","订单不存在或不可访问");return read(r);}}}
    public List<Order> orders(FamilyService.Identity actor,String family){requireStorage();try(var c=connection()){c.setAutoCommit(false);lock(c);role(c,family,actor.id(),true);var orders=new ArrayList<Order>();try(var s=c.prepareStatement("SELECT * FROM ct_billing_orders WHERE family_id=? ORDER BY created_at DESC LIMIT 100")){s.setString(1,family);try(var r=s.executeQuery()){while(r.next())orders.add(read(r).view());}}c.commit();return orders;}catch(SQLException e){throw unavailable(e);}}
    public Order order(FamilyService.Identity actor,String family,String orderId){requireStorage();try(var c=connection()){c.setAutoCommit(false);lock(c);role(c,family,actor.id(),true);var result=order(c,family,orderId).view();c.commit();return result;}catch(SQLException e){throw unavailable(e);}}
    public OrderResult createOrder(FamilyService.Identity actor,String family,String productId,String requestId,String clientIp){
        id(family);id(productId);id(requestId);if(!Set.of("owner","admin").contains(localRole(actor,family)))throw denied();requirePayment();requireStorage();String digest=sha(productId.getBytes(StandardCharsets.UTF_8));Order order;
        try(var c=connection()){c.setAutoCommit(false);lock(c);role(c,family,actor.id(),true);requirePayment();
            Stored existing=null;try(var s=c.prepareStatement("SELECT * FROM ct_billing_orders WHERE family_id=? AND request_id=?")){s.setString(1,family);s.setString(2,requestId);try(var r=s.executeQuery()){if(r.next())existing=read(r);}}
            if(existing!=null){if(!existing.view().actorId().equals(actor.id())||!existing.requestHash().equals(digest))throw new ApiException(409,"ORDER_REQUEST_REUSED","此操作编号已用于其他购买请求");order=existing.view();}
            else{Product p=products.product(productId);String orderId=UUID.randomUUID().toString(),trade=UUID.randomUUID().toString().replace("-","");
                try(var s=c.prepareStatement("INSERT INTO ct_billing_orders(id,family_id,actor_id,request_id,request_sha256,out_trade_no,product_id,feature,title,amount_minor,currency,duration_days,provider,merchant_id,app_id,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'CREATED',current_timestamp,current_timestamp)")){
                    s.setString(1,orderId);s.setString(2,family);s.setString(3,actor.id());s.setString(4,requestId);s.setString(5,digest);s.setString(6,trade);s.setString(7,p.id());s.setString(8,p.feature().name());s.setString(9,p.title());s.setLong(10,p.amountMinor());s.setString(11,p.currency());s.setInt(12,p.durationDays());s.setString(13,provider.name());s.setString(14,provider.merchantId());s.setString(15,provider.appId());s.executeUpdate();}
                order=order(c,family,orderId).view();
            }c.commit();
        }catch(SQLException e){throw unavailable(e);}
        if(!order.status().equals("CREATED"))return new OrderResult(order,provider.configured());
        // A committed CREATED order keeps the same out_trade_no across uncertain provider failures.
        Checkout checkout=provider.createCheckout(new CheckoutRequest(order.id(),order.outTradeNo(),order.title(),order.amountMinor(),order.currency(),clientIp,notifyUrl));
        if(checkout==null||checkout.checkoutUrl()==null||!checkout.checkoutUrl().startsWith("https://")||checkout.checkoutUrl().length()>4000)throw new ApiException(502,"INVALID_PAYMENT_REPLY","支付服务返回无效，原订单保留待核对");
        try(var c=connection()){c.setAutoCommit(false);lock(c);role(c,family,actor.id(),true);try(var s=c.prepareStatement("UPDATE ct_billing_orders SET status='PENDING',checkout_url=?,provider_reference=?,updated_at=current_timestamp WHERE id=? AND status='CREATED'")){s.setString(1,checkout.checkoutUrl());s.setString(2,checkout.providerReference());s.setString(3,order.id());s.executeUpdate();}Order result=order(c,family,order.id()).view();c.commit();return new OrderResult(result,true);}catch(SQLException e){throw unavailable(e);}
    }
    public Object grantManual(FamilyService.Identity actor,String family,Feature feature,Instant startsAt,Instant expiresAt){
        requireStorage();if(!operators.contains(actor.id()))throw new ApiException(403,"OPERATOR_REQUIRED","人工测试权益仅可由已配置的操作员授予");if(feature==null||startsAt==null||expiresAt==null||!expiresAt.isAfter(startsAt)||Duration.between(startsAt,expiresAt).toDays()>3660)throw invalid("人工测试权益时间范围不正确");
        try(var c=connection()){c.setAutoCommit(false);lock(c);try(var s=c.prepareStatement("SELECT id FROM ct_families WHERE id=?")){s.setString(1,family);try(var r=s.executeQuery()){if(!r.next())throw denied();}}String id=UUID.randomUUID().toString();
            try(var s=c.prepareStatement("INSERT INTO ct_billing_grants(id,family_id,feature,source,starts_at,expires_at,granted_by) VALUES(?,?,?,'manual-test',?,?,?)")){s.setString(1,id);s.setString(2,family);s.setString(3,feature.name());s.setTimestamp(4,Timestamp.from(startsAt));s.setTimestamp(5,Timestamp.from(expiresAt));s.setString(6,actor.id());s.executeUpdate();}c.commit();return Map.of("id",id,"feature",feature,"source","manual-test","startsAt",startsAt.toString(),"expiresAt",expiresAt.toString(),"realPurchase",false);
        }catch(SQLException e){throw unavailable(e);}
    }
    public void notification(String providerName,Map<String,String> headers,byte[] rawBody){requireStorage();requirePayment();if(!provider.name().equals(providerName))throw new ApiException(400,"PAYMENT_PROVIDER_MISMATCH","支付来源不匹配");if(rawBody==null||rawBody.length>65536)throw invalid("回调请求过大");ProviderEvent event=provider.verifyNotification(headers,rawBody);applyVerifiedEvent(event,sha(rawBody));}
    private void applyVerifiedEvent(ProviderEvent event,String digest){
        if(event==null||event.eventId()==null||event.eventId().isBlank()||event.eventId().length()>200||event.type()==null||event.occurredAt()==null||event.outTradeNo()==null||!event.outTradeNo().matches("[A-Za-z0-9_-]{1,32}"))throw invalid("支付事件不完整");
        try(var c=connection()){c.setAutoCommit(false);lock(c);Stored stored;try(var s=c.prepareStatement("SELECT * FROM ct_billing_orders WHERE out_trade_no=?")){s.setString(1,event.outTradeNo());try(var r=s.executeQuery()){if(!r.next())throw new ApiException(404,"ORDER_NOT_FOUND","回调订单不存在");stored=read(r);}}
            Order order=stored.view();if(!order.provider().equals(provider.name())||!Objects.equals(event.merchantId(),stored.merchantId())||!Objects.equals(event.appId(),stored.appId())||event.amountMinor()!=order.amountMinor()||!order.currency().equals(event.currency()))throw new ApiException(400,"PAYMENT_EVENT_MISMATCH","支付事件金额、币种或商户不匹配");
            try(var s=c.prepareStatement("SELECT request_sha256,order_id FROM ct_billing_event_inbox WHERE provider=? AND event_id=?")){s.setString(1,provider.name());s.setString(2,event.eventId());try(var r=s.executeQuery()){if(r.next()){if(!r.getString(1).equals(digest)||!r.getString(2).equals(order.id()))throw new ApiException(409,"PAYMENT_EVENT_REUSED","支付事件编号内容不一致");c.commit();return;}}}
            String status=order.status(),next=status;
            if(event.type()==EventType.PAID){
                if(event.transactionId()==null||event.transactionId().isBlank()||event.transactionId().length()>200)throw invalid("支付流水编号缺失");
                if(stored.transactionId()!=null&&!stored.transactionId().equals(event.transactionId()))throw new ApiException(409,"PAYMENT_TRANSACTION_MISMATCH","支付流水与订单不一致");
                if(!Set.of("REFUND_PENDING","REFUNDED").contains(status)){
                    next="PAID";Instant start=event.occurredAt(),end=start.plus(Duration.ofDays(order.durationDays()));
                    try(var s=c.prepareStatement("INSERT INTO ct_billing_grants(id,family_id,feature,source,order_id,starts_at,expires_at,granted_by) VALUES(?,?,?,'purchase',?,?,?,?) ON CONFLICT(order_id) DO NOTHING")){s.setString(1,UUID.randomUUID().toString());s.setString(2,order.familyId());s.setString(3,order.feature().name());s.setString(4,order.id());s.setTimestamp(5,Timestamp.from(start));s.setTimestamp(6,Timestamp.from(end));s.setString(7,"provider:"+provider.name());s.executeUpdate();}
                }
            }else if(event.type()==EventType.REFUNDED||event.type()==EventType.REFUND_FAILED){
                if(order.outRefundNo()==null||!order.outRefundNo().equals(event.outRefundNo())||event.refundAmountMinor()!=order.amountMinor())throw new ApiException(400,"REFUND_EVENT_MISMATCH","退款单或退款金额不匹配");
                if(event.type()==EventType.REFUNDED){next="REFUNDED";try(var s=c.prepareStatement("UPDATE ct_billing_grants SET revoked_at=coalesce(revoked_at,?) WHERE order_id=?")){s.setTimestamp(1,Timestamp.from(event.occurredAt()));s.setString(2,order.id());s.executeUpdate();}}
                else if(status.equals("REFUND_PENDING"))next="PAID";
            }else if(!Set.of("PAID","REFUND_PENDING","REFUNDED").contains(status))next=event.type()==EventType.CLOSED?"CLOSED":"FAILED";
            try(var s=c.prepareStatement("UPDATE ct_billing_orders SET status=?,transaction_id=coalesce(transaction_id,?),updated_at=current_timestamp WHERE id=?")){s.setString(1,next);s.setString(2,event.type()==EventType.PAID?event.transactionId():null);s.setString(3,order.id());s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT INTO ct_billing_event_inbox VALUES(?,?,?,?,?,current_timestamp)")){s.setString(1,provider.name());s.setString(2,event.eventId());s.setString(3,digest);s.setString(4,order.id());s.setString(5,event.type().name());s.executeUpdate();}c.commit();
        }catch(SQLException e){throw unavailable(e);}
    }
    public Order query(FamilyService.Identity actor,String family,String orderId){Order order=order(actor,family,orderId);requirePayment();ProviderEvent event=order.status().equals("REFUND_PENDING")?provider.queryRefund(order.outRefundNo()):provider.queryPayment(order.outTradeNo());if(event!=null){try{applyVerifiedEvent(event,sha(mapper.writeValueAsBytes(event)));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw unavailable(e);}}return order(actor,family,orderId);}
    public Order close(FamilyService.Identity actor,String family,String orderId){Order order=order(actor,family,orderId);requirePayment();if(Set.of("CREATED","PENDING").contains(order.status())){provider.close(order.outTradeNo());try(var c=connection()){c.setAutoCommit(false);lock(c);role(c,family,actor.id(),true);try(var s=c.prepareStatement("UPDATE ct_billing_orders SET status='CLOSED',updated_at=current_timestamp WHERE id=? AND status IN ('CREATED','PENDING')")){s.setString(1,order.id());s.executeUpdate();}c.commit();}catch(SQLException e){throw unavailable(e);}}return order(actor,family,orderId);}
    public Order refund(FamilyService.Identity actor,String family,String orderId,String reason){
        requireStorage();requirePayment();if(reason==null||reason.isBlank()||reason.length()>80)throw invalid("退款原因需为1至80字");Order order;
        try(var c=connection()){c.setAutoCommit(false);lock(c);role(c,family,actor.id(),true);order=order(c,family,orderId).view();if(!Set.of("PAID","REFUND_PENDING","REFUNDED").contains(order.status()))throw invalid("此订单尚未支付，不能退款");if(order.status().equals("REFUNDED")){c.commit();return order;}
            try(var s=c.prepareStatement("UPDATE ct_billing_orders SET status='REFUND_PENDING',out_refund_no=coalesce(out_refund_no,?),updated_at=current_timestamp WHERE id=?")){s.setString(1,UUID.randomUUID().toString().replace("-",""));s.setString(2,order.id());s.executeUpdate();}order=order(c,family,order.id()).view();c.commit();
        }catch(SQLException e){throw unavailable(e);}
        provider.refund(new RefundRequest(order.outTradeNo(),order.outRefundNo(),order.amountMinor(),order.amountMinor(),order.currency(),reason));return order(actor,family,orderId);
    }
}
