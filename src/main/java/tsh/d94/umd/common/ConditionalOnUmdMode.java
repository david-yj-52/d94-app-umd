package tsh.d94.umd.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * umd.mode 가 지정한 값 중 하나일 때만 Bean 을 등록한다.
 * <pre>
 * &#64;ConditionalOnUmdMode({UmdMode.SERVER, UmdMode.BOTH})  // server 기능
 * &#64;ConditionalOnUmdMode({UmdMode.LOCAL, UmdMode.BOTH})   // local 기능
 * &#64;ConditionalOnUmdMode(UmdMode.LOCAL)                   // local 단독일 때만 (예: 보안 설정)
 * </pre>
 * umd.mode 미설정 시 SERVER 로 본다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnUmdModeCondition.class)
public @interface ConditionalOnUmdMode {

	UmdMode[] value();
}
