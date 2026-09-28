package com.relay.security;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
class ManagementCorsTest {
    @Test void explicitOriginsAndDisabledList(){
        assertThat(ManagementCors.origins(" ")).isEmpty();
        assertThat(ManagementCors.origins("http://localhost:5173, https://console.test,http://localhost:5173"))
            .containsExactly("http://localhost:5173","https://console.test");
    }
    @ParameterizedTest @ValueSource(strings={"*","null","https://host/","https://user:secret@host","https://host?q=secret","http://host:0","http://host:65536","http://host,","file:///tmp"})
    void badConfiguration(String value){assertThatThrownBy(() -> ManagementCors.origins(value))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("RELAY_ALLOWED_ORIGINS").hasMessageNotContaining("secret");}
}
