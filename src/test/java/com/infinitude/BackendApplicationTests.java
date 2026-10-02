package com.infinitude;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"MAIL_HOST=smtp.test.invalid", "MAIL_FROM=no-reply@test.invalid"})
class BackendApplicationTests {

	@MockitoBean
	JavaMailSender mailSender;

	@Test
	void contextLoads() {
	}

}
