package tsh.d94.umd.server;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import tsh.d94.umd.common.spec.StreamHttpReply;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;

/**
 * corrId 별 응답 대기 Future 보관소.
 * 응답 수신 / 타임아웃 / 실패 중 먼저 일어난 쪽으로 완료되며, 완료 시 자동으로 제거된다.
 */
@Component
@ConditionalOnUmdMode({UmdMode.SERVER, UmdMode.BOTH})
public class PendingReplyRegistry {

	private final Map<String, CompletableFuture<StreamHttpReply>> pending = new ConcurrentHashMap<>();

	/**
	 * @return timeout 경과 시 {@link java.util.concurrent.TimeoutException} 으로 완료되는 Future
	 */
	public CompletableFuture<StreamHttpReply> register(String corrId, Duration timeout) {
		CompletableFuture<StreamHttpReply> future = new CompletableFuture<>();
		if (pending.putIfAbsent(corrId, future) != null) {
			throw new IllegalStateException("이미 대기 중인 corrId: " + corrId);
		}
		future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
			.whenComplete((reply, error) -> pending.remove(corrId, future));
		return future;
	}

	/**
	 * @return 대기 중인 요청이 있어 완료시켰으면 true, 이미 타임아웃 등으로 없으면 false (늦은 응답)
	 */
	public boolean complete(StreamHttpReply reply) {
		CompletableFuture<StreamHttpReply> future = pending.remove(reply.getCorrId());
		return future != null && future.complete(reply);
	}

	public void fail(String corrId, Throwable cause) {
		CompletableFuture<StreamHttpReply> future = pending.remove(corrId);
		if (future != null) {
			future.completeExceptionally(cause);
		}
	}

	public void failAll(Throwable cause) {
		pending.keySet().forEach(corrId -> fail(corrId, cause));
	}

	public int size() {
		return pending.size();
	}
}
