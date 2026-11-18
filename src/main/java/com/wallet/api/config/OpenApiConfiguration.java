package com.wallet.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfiguration {

    private static final String API_KEY_SCHEME = "apiKey";

    @Bean
    public OpenAPI walletOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Wallet API")
                        .version("1.0.0")
                        .description("""
                                Serviço de carteira digital com consistência garantida pelo Oracle.

                                * Todo crédito/débito passa por `PKG_WALLET`, que valida a operação,
                                  serializa o acesso à carteira com `SELECT ... FOR UPDATE`, aplica a
                                  idempotência e grava extrato + saldo na mesma transação.
                                * Erros seguem `application/problem+json` (RFC 9457) com um `code`
                                  estável, o mesmo nome usado pelo banco em `ORA-20xxx`.
                                * Autenticação por API key no cabeçalho `X-API-Key`.
                                """)
                        .contact(new Contact().name("Wallet API"))
                        .license(new License().name("MIT")))
                .servers(List.of(new Server().url("/").description("Servidor atual")))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")
                                .description("Chave de API configurada em WALLET_API_KEYS")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
