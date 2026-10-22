package com.risk.gateway.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 部署注入的个人凭证，不接受客户端自报身份。可由后续 OIDC 认证替换。 */
@Component
public class AdminIdentities {
    public record Credential(String token, String role) {}
    public record Identity(String subject, String role) {}
    public static final String ATTRIBUTE="risk.adminIdentity";
    private final Map<String,Credential> identities;
    public AdminIdentities(ObjectMapper mapper,@Value("${risk.admin-identities-json:{}}") String json) {
        try { identities=Map.copyOf(mapper.readValue(json,new TypeReference<Map<String,Credential>>() {})); }
        catch (Exception e) { throw new IllegalArgumentException("个人管理员凭证配置错误",e); }
        Set<String> tokens=new HashSet<>();
        identities.forEach((name,credential)-> {
            if (!name.matches("[A-Za-z0-9._:-]{1,128}") || credential.token()==null || credential.token().length()<32 ||
                !Set.of("READER","EDITOR","PUBLISHER").contains(credential.role()) || !tokens.add(credential.token()))
                throw new IllegalArgumentException("个人管理员凭证、角色或唯一性不合法");
        });
    }
    public boolean enabled() { return !identities.isEmpty(); }
    public Identity authenticate(String authorization) {
        if (authorization==null) return null;
        Identity result=null;
        for (var entry:identities.entrySet()) {
            if (MessageDigest.isEqual(("Bearer "+entry.getValue().token()).getBytes(StandardCharsets.UTF_8),authorization.getBytes(StandardCharsets.UTF_8)))
                result=new Identity(entry.getKey(),entry.getValue().role());
        }
        return result;
    }
    public static String actor(jakarta.servlet.http.HttpServletRequest request) {
        Object identity=request.getAttribute(ATTRIBUTE);
        return identity instanceof Identity value ? value.subject() : "admin-token";
    }
    public static boolean permits(Identity identity,String method,String path) {
        if (method.equals("GET")) return true;
        if (identity.role().equals("READER")) return false;
        if (path.endsWith("/approve") || path.endsWith("/publish") || path.endsWith("/rollback") || path.endsWith("/state") || path.equals("/api/v1/admin/rollout"))
            return identity.role().equals("PUBLISHER");
        return true;
    }
}
