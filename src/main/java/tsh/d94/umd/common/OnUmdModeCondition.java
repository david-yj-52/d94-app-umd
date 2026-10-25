package tsh.d94.umd.common;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

class OnUmdModeCondition implements Condition {

	static final String MODE_PROPERTY = "umd.mode";

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnUmdMode.class.getName());
		if (attributes == null) {
			return false;
		}
		UmdMode[] modes = (UmdMode[])attributes.get("value");
		UmdMode current = currentMode(context.getEnvironment().getProperty(MODE_PROPERTY));
		return Arrays.asList(modes).contains(current);
	}

	static UmdMode currentMode(String value) {
		if (value == null || value.isBlank()) {
			return UmdMode.SERVER;
		}
		try {
			return UmdMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException(MODE_PROPERTY + " 값이 올바르지 않습니다 (server|local|both): " + value, e);
		}
	}
}
