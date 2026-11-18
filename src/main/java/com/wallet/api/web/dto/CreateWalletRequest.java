package com.wallet.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWalletRequest(

        @NotBlank(message = "nome do titular é obrigatório")
        @Size(max = 120, message = "nome do titular deve ter no máximo 120 caracteres")
        String holderName,

        @Size(max = 32, message = "documento do titular deve ter no máximo 32 caracteres")
        String holderDocument
) {
}
