package com.wallet.api.web;

import com.wallet.api.application.WalletApplicationService;
import com.wallet.api.config.PaginationProperties;
import com.wallet.api.domain.TransactionFilter;
import com.wallet.api.domain.TransactionReceipt;
import com.wallet.api.domain.TransactionSearchResult;
import com.wallet.api.domain.TransactionType;
import com.wallet.api.domain.exception.ErrorCode;
import com.wallet.api.domain.exception.InvalidRequestException;
import com.wallet.api.domain.Wallet;
import com.wallet.api.web.dto.CreateWalletRequest;
import com.wallet.api.web.dto.PageResponse;
import com.wallet.api.web.dto.TransactionReceiptResponse;
import com.wallet.api.web.dto.TransactionRequest;
import com.wallet.api.web.dto.TransactionResponse;
import com.wallet.api.web.dto.WalletResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.OffsetDateTime;

@RestController
@RequestMapping(path = "/api/v1/wallets", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Carteiras", description = "Criação de carteira, consulta de saldo, extrato e registro de operações")
public class WalletController {

    private final WalletApplicationService service;
    private final PaginationProperties pagination;

    public WalletController(WalletApplicationService service, PaginationProperties pagination) {
        this.service = service;
        this.pagination = pagination;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cria uma carteira",
            description = "Cria a carteira com saldo inicial 0.00 em BRL e retorna o identificador gerado pela sequence.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Carteira criada",
                    headers = @Header(name = "Location", description = "URI da carteira criada")),
            @ApiResponse(responseCode = "400", description = "Dados do titular inválidos",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<WalletResponse> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        Wallet wallet = service.createWallet(request.holderName(), request.holderDocument());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(wallet.id())
                .toUri();
        return ResponseEntity.created(location).body(WalletResponse.from(wallet));
    }

    @GetMapping("/{walletId}")
    @Operation(summary = "Consulta saldo e dados da carteira")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Carteira encontrada"),
            @ApiResponse(responseCode = "404", description = "Carteira não encontrada",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public WalletResponse getWallet(@PathVariable long walletId) {
        return WalletResponse.from(service.getWallet(walletId));
    }

    @GetMapping("/{walletId}/transactions")
    @Operation(summary = "Lista o extrato da carteira",
            description = "Paginado, ordenado do mais recente para o mais antigo, com filtro por período e tipo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Extrato retornado"),
            @ApiResponse(responseCode = "400", description = "Filtro ou paginação inválidos",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Carteira não encontrada",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public PageResponse<TransactionResponse> listTransactions(
            @PathVariable long walletId,
            @Parameter(description = "Início do período, ISO-8601 com offset (ex.: 2026-01-01T00:00:00Z)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "Fim do período, ISO-8601 com offset (inclusive)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @Parameter(description = "Filtra por tipo (CREDIT ou DEBIT)")
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {

        int effectiveSize = size == null ? pagination.effectiveDefaultPageSize() : size;
        TransactionSearchResult result = service.searchTransactions(
                walletId,
                new TransactionFilter(from, to, parseType(type)),
                page,
                effectiveSize);

        return PageResponse.of(
                result.items().stream().map(TransactionResponse::from).toList(),
                result.page(),
                result.size(),
                result.totalElements());
    }

    @PostMapping(path = "/{walletId}/transactions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Registra um crédito ou débito",
            description = "Grava via package PL/SQL. Exige o cabeçalho Idempotency-Key: a mesma chave "
                    + "com o mesmo payload devolve o resultado original (replayed=true) e não altera o saldo; "
                    + "a mesma chave com payload diferente retorna 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Operação aplicada",
                    headers = @Header(name = "Idempotent-Replayed", description = "false")),
            @ApiResponse(responseCode = "200", description = "Operação já aplicada (replay idempotente)",
                    headers = @Header(name = "Idempotent-Replayed", description = "true")),
            @ApiResponse(responseCode = "400", description = "Requisição inválida",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Carteira não encontrada",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Chave de idempotência reutilizada com outro payload",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Saldo insuficiente",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<TransactionReceiptResponse> registerTransaction(
            @PathVariable long walletId,
            @Parameter(description = "Chave única da operação, 8 a 80 caracteres [A-Za-z0-9._:-]")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransactionRequest request) {

        TransactionReceipt receipt = service.registerTransaction(
                walletId,
                request.type(),
                request.amount(),
                request.description(),
                idempotencyKey);

        HttpStatus status = receipt.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .header("Idempotent-Replayed", Boolean.toString(receipt.replayed()))
                .body(TransactionReceiptResponse.from(receipt));
    }

    private static TransactionType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TransactionType.from(raw);
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException(ErrorCode.INVALID_TRANSACTION_TYPE,
                    "type deve ser CREDIT ou DEBIT");
        }
    }
}
