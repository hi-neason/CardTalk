package com.cardtalk.family;

import com.cardtalk.api.*;
import com.cardtalk.billing.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import jakarta.servlet.http.Cookie;
import java.util.List;
import static com.cardtalk.api.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FamilyExpansionTest {
    final FamilyService families=mock(FamilyService.class);
    final BillingService billing=mock(BillingService.class);
    final TrialService trials=mock(TrialService.class);
    final FamilyService.Identity actor=new FamilyService.Identity("account-one","owner");
    ExpansionRequest request(boolean profile,boolean scene){return new ExpansionRequest("nonce",1L,"local-room",List.of(new Card("apple","苹果",null,null)),new Context("adult",scene?new Scene("custom-scene-kitchen","厨房","吃饭"):null,List.of(),profile?new Profile("本人",70,null,null,null,null,null):null,null));}
    MockHttpServletRequest session(String role){var r=new MockHttpServletRequest();r.setCookies(new Cookie("cardtalk_family","cookie"));when(families.authenticate("cookie")).thenReturn(actor);when(families.families(actor)).thenReturn(List.of(new FamilyService.FamilyView("family-one","家庭",role,List.of())));return r;}
    @Test void formalFamilyChecksIndependentRightsBeforeUsingTrialAllowance(){
        var r=session("owner");var input=request(true,true);var controller=new FamilyExpansionController(families,billing,trials,true);
        controller.expand("family-one","anonymous-quota",input,r);
        verify(billing).requireAdvancedWrite(actor,"family-one",ProductsConfig.Feature.profiles);
        verify(billing).requireAdvancedWrite(actor,"family-one",ProductsConfig.Feature.scenarios);
        verify(trials).expandAuthorized("anonymous-quota",input,true,true,true,"family-family-one-account-one");
        doThrow(new ApiException(403,"FEATURE_DISABLED","未开通")).when(billing).requireAdvancedWrite(actor,"family-one",ProductsConfig.Feature.profiles);
        clearInvocations(trials);assertThrows(ApiException.class,()->controller.expand("family-one","anonymous-quota",input,r));verifyNoInteractions(trials);
    }
    @Test void basicInputRequiresMembershipButNoPurchasedManagement(){var r=session("member");var input=request(false,false);new FamilyExpansionController(families,billing,trials,true).expand("family-one","anonymous-quota",input,r);verifyNoInteractions(billing);verify(trials).expandAuthorized(anyString(),eq(input),eq(true),eq(true),eq(true),anyString());}
    @Test void helperAndOtherFamiliesCannotRequestExpansion(){var r=session("helper");var c=new FamilyExpansionController(families,billing,trials,true);assertThrows(ApiException.class,()->c.expand("family-one","quota",request(false,false),r));var finalRequest=session("owner");assertThrows(ApiException.class,()->c.expand("other-family","quota",request(true,true),finalRequest));verifyNoInteractions(trials,billing);}
    @Test void invalidSuppliedCookieDoesNotFallBackToBearer(){var r=session("owner");r.addHeader("Authorization","Bearer alternate");when(families.authenticate("cookie")).thenThrow(new ApiException(401,"ACCOUNT_UNAUTHORIZED","登录失效"));assertThrows(ApiException.class,()->new FamilyExpansionController(families,billing,trials,true).expand("family-one","quota",request(false,false),r));verify(families,never()).authenticate("alternate");verifyNoInteractions(trials,billing);}
}
