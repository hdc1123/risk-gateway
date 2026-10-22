package com.risk.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminIdentitiesTest {
    @Test void personalTokensAndRoles() {
        String token="a".repeat(32);
        var identities=new AdminIdentities(new ObjectMapper(),"{\"alice\":{\"token\":\""+token+"\",\"role\":\"EDITOR\"}}");
        var user=identities.authenticate("Bearer "+token);
        assertEquals("alice",user.subject());
        assertNull(identities.authenticate("Bearer wrong"));
        assertTrue(AdminIdentities.permits(user,"PUT","/api/v1/admin/rules/x/draft"));
        assertFalse(AdminIdentities.permits(user,"POST","/api/v1/admin/rules/x/approve"));
        assertFalse(AdminIdentities.permits(user,"PUT","/api/v1/admin/rollout"));
        assertTrue(AdminIdentities.permits(new AdminIdentities.Identity("bob","PUBLISHER"),"POST","/api/v1/admin/rules/x/publish"));
    }
    @Test void rejectsWeakCredentials() {
        assertThrows(IllegalArgumentException.class,()->new AdminIdentities(new ObjectMapper(),"{\"alice\":{\"token\":\"weak\",\"role\":\"EDITOR\"}}"));
    }
}
