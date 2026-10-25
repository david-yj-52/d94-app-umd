package tsh.d94.umd.server;

import java.util.concurrent.CompletableFuture;

import tsh.d94.umd.common.spec.StreamHttpReply;

/**
 * 발행한 요청의 corrId 와 응답 Future.
 * 타임아웃/실패 시에도 corrId 로 추적할 수 있도록 함께 돌려준다.
 */
public record StreamCall(String corrId, CompletableFuture<StreamHttpReply> reply) {
}
