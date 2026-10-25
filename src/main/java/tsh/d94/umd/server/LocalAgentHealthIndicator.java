package tsh.d94.umd.server;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;

/**
 * /actuator/health 의 localAgent 항목 — local 생존 여부를 <b>정보로만</b> 보여준다.
 * 집 PC 가 꺼져 있다고 server 를 비정상(재시작 대상)으로 볼 이유는 없으므로 항상 UP.
 */
@Component("localAgent")
@RequiredArgsConstructor
@ConditionalOnUmdMode({UmdMode.SERVER, UmdMode.BOTH})
public class LocalAgentHealthIndicator implements HealthIndicator {

	private final LocalAliveChecker localAliveChecker;

	@Override
	public Health health() {
		boolean alive;
		try {
			alive = localAliveChecker.isAlive();
		} catch (RuntimeException e) {
			alive = localAliveChecker.lastKnownAlive();
		}
		return Health.up().withDetail("alive", alive).build();
	}
}
