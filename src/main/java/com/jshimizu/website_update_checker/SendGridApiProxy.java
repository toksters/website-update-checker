package com.jshimizu.website_update_checker;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SendGridApiProxy {

  private static final String SENDGRID_API_KEY = "";

  public void sendEmail(String toEmail, String subject, String emailContent) {
    Email from = new Email("toksters@gmail.com");
    Email to = new Email(toEmail);
    Content content = new Content("text/html", emailContent);
    Mail mail = new Mail(from, subject, to, content);

    SendGrid sg = new SendGrid(SENDGRID_API_KEY);
    Request request = new Request();
    try {
      request.setMethod(Method.POST);
      request.setEndpoint("mail/send");
      request.setBody(mail.build());
      Response response = sg.api(request);
      System.out.println(response.getStatusCode());
      System.out.println(response.getBody());
      System.out.println(response.getHeaders());
    } catch (IOException e) {
      log.error("Error occurred while sending email", e);
    }
  }

}
