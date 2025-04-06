package com.jshimizu.website_update_checker;

import org.junit.jupiter.api.Test;

public class AnalogFilmNycJobTest {

  @Test
  public void testJob() {
    (new AnalogFilmNycJob(new SendGridApiProxy())).runJob();
  }

}
