package tsh.d94.umd.server;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import tsh.d94.umd.common.UmdProperties;

class ReplyStreamHealthIndicatorTest {

	private final ReplyStreamListener listener = mock(ReplyStreamListener.class);
	private final PendingReplyRegistry registry = new PendingReplyRegistry();
	private final UmdProperties properties = new UmdProperties();

	private ReplyStreamHealthIndicator indicator;

	@BeforeEach
	void setUp() {
		properties.setInstanceId("umd-1");
		indicator = new ReplyStreamHealthIndicator(listener, registry, properties);
	}

	@Test
	void up_when_listening_and_reads_healthy() {
		when(listener.isRunning()).thenReturn(true);
		when(listener.isReadHealthy()).thenReturn(true);

		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
		assertThat(indicator.health().getDetails()).containsEntry("stream", "d94:umd:rep:umd-1");
	}

	@Test
	void down_when_recent_read_errors() {
		when(listener.isRunning()).thenReturn(true);
		when(listener.isReadHealthy()).thenReturn(false);

		assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
	}

	@Test
	void down_when_not_listening() {
		when(listener.isRunning()).thenReturn(false);
		when(listener.isReadHealthy()).thenReturn(true);

		assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
	}
}
