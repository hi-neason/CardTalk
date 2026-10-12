package com.cardtalk.family;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import java.io.*;
import java.util.*;

@Component
public class FamilyRequestFilter extends OncePerRequestFilter {
    private static final int MAX_BODY=32768;
    private final Set<String> origins;
    private final int assetMaxBytes;
    public FamilyRequestFilter(String origins) { this(origins,16777216); }
    @org.springframework.beans.factory.annotation.Autowired
    public FamilyRequestFilter(@Value("${cardtalk.family-allowed-origins:http://127.0.0.1:5173,http://localhost:5173}") String origins,@Value("${cardtalk.asset-max-bytes:16777216}") int assetMaxBytes) {
        if(assetMaxBytes<1||assetMaxBytes>134217728) throw new IllegalArgumentException("Invalid asset body limit");
        this.origins=Set.copyOf(Arrays.asList(origins.split(",")));this.assetMaxBytes=assetMaxBytes;
    }
    protected boolean shouldNotFilter(HttpServletRequest request) { String path=UrlPathHelper.defaultInstance.getPathWithinApplication(request).replaceAll(";[^/]*","");return !(path.startsWith("/api/v1/accounts/")||path.equals("/api/v1/families")||path.startsWith("/api/v1/families/")||path.equals("/api/v1/reports/narrative")||path.startsWith("/api/v1/support/")||path.startsWith("/api/v1/operations/")); }
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");
        if(Set.of("GET","HEAD").contains(request.getMethod())) { chain.doFilter(request,response);return; }
        String origin=request.getHeader("Origin");
        // Cookies are same-site, and browser writes must originate from the explicitly configured UI.
        boolean cookieAuth=request.getCookies()!=null&&Arrays.stream(request.getCookies()).anyMatch(c->c.getName().equals("cardtalk_family"));
        if((cookieAuth&&origin==null)||(origin!=null&&!origins.contains(origin))) { reject(response,403,"ORIGIN_DENIED");return; }
        if("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) { reject(response,403,"ORIGIN_DENIED");return; }
        String path=UrlPathHelper.defaultInstance.getPathWithinApplication(request).replaceAll(";[^/]*","");
        boolean assetUpload=request.getMethod().equals("POST")&&path.matches("/api/v1/families/[A-Za-z0-9_-]{1,100}/assets");
        boolean syncPush=request.getMethod().equals("POST")&&path.matches("/api/v1/families/[A-Za-z0-9_-]{1,100}/sync/push");
        boolean contentPublication=request.getMethod().equals("POST")&&path.equals("/api/v1/operations/content/versions");
        int limit=assetUpload?assetMaxBytes:syncPush?2*1024*1024:contentPublication?512*1024:MAX_BODY;
        if(assetUpload&&(request.getContentType()==null||!Set.of("image/png","image/jpeg","image/webp").contains(request.getContentType()))) { reject(response,415,"RASTER_REQUIRED");return; }
        if(request.getContentLengthLong()>limit) { reject(response,413,"BODY_TOO_LARGE");return; }
        byte[] body=request.getInputStream().readNBytes(limit+1);
        if(body.length>limit) { reject(response,413,"BODY_TOO_LARGE");return; }
        if(!assetUpload&&body.length>0&&(request.getContentType()==null||!request.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json"))) { reject(response,415,"JSON_REQUIRED");return; }
        var wrapped=new HttpServletRequestWrapper(request) {
            public ServletInputStream getInputStream() {
                var input=new ByteArrayInputStream(body);
                return new ServletInputStream() { public int read() { return input.read(); } public boolean isFinished() { return input.available()==0; } public boolean isReady() { return true; } public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); } };
            }
            public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8)); }
        };
        chain.doFilter(wrapped,response);
    }
    private void reject(HttpServletResponse response,int status,String code) throws IOException { response.setStatus(status);response.setContentType("application/json");response.getWriter().write("{\"code\":\""+code+"\",\"message\":\"请求未通过安全校验\"}"); }
}
