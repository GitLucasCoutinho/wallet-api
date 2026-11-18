package com.wallet.api.web.dto;

import com.wallet.api.domain.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TransactionRequest(

        @NotNull(message = "tipo é obrigatório")
        TransactionType type,

        @NotNull(message = "valor é obrigatório")
        @DecimalMin(value = "0.01", message = "valor deve ser maior que zero")
        @Digits(integer = 17, fraction = 2, message = "valor deve ter no máximo 2 casas decimais")
        BigDecimal amount,

        @Size(max = 255, message = "descrição deve ter no máximo 255 caracteres")
        String description
) {
}
