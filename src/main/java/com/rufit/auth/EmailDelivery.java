package com.rufit.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** 발송 실패를 가입 성공으로 처리하지 않으며 인증값을 로그로 대체하지 않습니다. */
@Component
@Profile("social")
public class EmailDelivery {
  private final ObjectProvider<JavaMailSender> sender;
  private final String from;
  public EmailDelivery(ObjectProvider<JavaMailSender> sender,
      @Value("${rufit.mail.from:noreply@rufit.test}") String from) {
    this.sender = sender;
    this.from = from;
  }
  public void send(String email, String token, String purpose) {
    try {
      JavaMailSender client = sender.getIfAvailable();
      if (client == null) throw new IllegalStateException();
      var message = new SimpleMailMessage();
      message.setFrom(from);
      message.setTo(email);
      String label = switch (purpose) {
        case "RESET" -> "비밀번호 재설정";
        case "LINK" -> "이메일 연결";
        default -> "회원가입";
      };
      message.setSubject("[루핏] " + label + " 인증");
      message.setText(label + "을 요청하셨습니다. 앱의 인증값 입력란에 아래 값을 붙여 넣어 주세요.\n\n"
        + token + "\n\n10분 동안 유효하며 한 번만 사용할 수 있습니다. 요청하지 않았다면 무시해 주세요.");
      client.send(message);
    } catch (Exception error) {
      throw new AuthFailure(503, "MAIL_UNAVAILABLE");
    }
  }
}
