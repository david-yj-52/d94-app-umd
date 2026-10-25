package tsh.d94.umd.server;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import tsh.d94.umd.common.spec.StreamHttpReply;

class PendingReplyRegistryTest {

	private final PendingReplyRegistry registry = new PendingReplyRegistry();

	private StreamHttpReply reply(String corrId) {
		return StreamHttpReply.builder().corrId(corrId).status(200).build();
	}

	@Test
	void complete_resolves_future_and_removes_entry() throws Exception {
		CompletableFuture<StreamHttpReply> future = registry.register("c1", Duration.ofSeconds(5));

		assertThat(registry.complete(reply("c1"))).isTrue();
		assertThat(future.get().getCorrId()).isEqualTo("c1");
		assertThat(registry.size()).isZero();
	}

	@Test
	void complete_unknown_corrId_returns_false() {
		assertThat(registry.complete(reply("nope"))).isFalse();
	}

	@Test
	void timeout_completes_exceptionally_and_late_reply_is_ignored() {
		CompletableFuture<StreamHttpReply> future = registry.register("c2", Duration.ofMillis(50));

		assertThatThrownBy(future::get)
			.isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(TimeoutException.class);
		assertThat(registry.size()).isZero();
		assertThat(registry.complete(reply("c2"))).isFalse();
	}

	@Test
	void duplicate_corrId_throws() {
		registry.register("c3", Duration.ofSeconds(5));

		assertThatThrownBy(() -> registry.register("c3", Duration.ofSeconds(5)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void failAll_fails_every_pending_future() {
		CompletableFuture<StreamHttpReply> f1 = registry.register("a", Duration.ofSeconds(5));
		CompletableFuture<StreamHttpReply> f2 = registry.register("b", Duration.ofSeconds(5));

		registry.failAll(new IllegalStateException("shutdown"));

		assertThat(f1).isCompletedExceptionally();
		assertThat(f2).isCompletedExceptionally();
		assertThat(registry.size()).isZero();
	}
}
