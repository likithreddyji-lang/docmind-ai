package com.substring.docmind;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "jwt.secret=testSecretKeyMustBeAtLeast32BytesLongForHmacSha256Security!",
    "spring.ai.openai.api-key=test-gemini-key"
})
@ActiveProfiles("dev")
class DocmindBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
