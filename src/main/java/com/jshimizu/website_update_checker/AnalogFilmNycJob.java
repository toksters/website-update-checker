package com.jshimizu.website_update_checker;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.time.Instant;
import java.time.Month;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@EnableAsync
@Slf4j
@Component
public class AnalogFilmNycJob {

  private static final String DELIMITER = ";;";

  @Value("${analogFilmNyc.destinationEmail}")
  private String destinationEmail;

  @Value("${analogFilmNyc.keywords}")
  private String[] keywords;

  private static final int INTERVAL_MINS = 1;

  private static final int INTERVAL = INTERVAL_MINS * 60 * 1000;

  private final SendGridApiProxy sendGridApiProxy;

  public AnalogFilmNycJob(SendGridApiProxy sendGridApiProxy) {
    this.sendGridApiProxy = sendGridApiProxy;
  }

  @Async
  @Scheduled(fixedDelay = INTERVAL)
  public void runJob(){
    log.info("Running Analog Film NYC Job... ");

    try {
      Map<MonthDay, List<MovieShowing>> showings = parseWebpage();
      Map<MonthDay, List<MovieShowing>> mostRecent = getMostRecentParsing();

      writeToFile(showings);


      Map<MonthDay, List<MovieShowing>> diffs = new HashMap<>();
      for (MonthDay key : showings.keySet()) {
        if (!mostRecent.containsKey(key)) {
          diffs.put(key, showings.get(key));
          continue;
        }
        List<MovieShowing> diff =
            showings.get(key).stream().filter((s) -> !mostRecent.get(key).contains(s))
                .toList();
        if (!diff.isEmpty()) {
          diffs.put(key, diff);
        }
      }

      // filter out diffs without keywords:
      Map<MonthDay, List<MovieShowing>> filteredDiffs = diffs.keySet().stream()
          .filter(k -> !diffs.get(k).stream().filter(this::doesShowingContainKeywords).toList().isEmpty())
          .collect(Collectors.toMap(k -> k, (k) -> diffs.get(k).stream().filter(this::doesShowingContainKeywords).toList()));

      if (filteredDiffs.isEmpty()) {
        log.info("Movie showings are the same from last parsing!");
      } else {
        log.info("Detected diffs! Sending email");
        sendEmail(filteredDiffs);
      }

    } catch (IOException e) {
      log.error("encountered IO exception when parsing webpage", e);
      return;
    }

    log.info("Finished running Analog Film NYC Job");
  }

  private Map<MonthDay, List<MovieShowing>> parseWebpage() throws IOException {
    Document doc = Jsoup.connect("https://analogfilmnyc.org/upcoming-screenings/").get();

    Elements elements = doc.select("div.entry-content");
    Element entryContents = elements.get(0);

    Map<MonthDay, List<MovieShowing>> showings = new HashMap<>();

    int i = 0;
    while (i < entryContents.children().size()) {
      Element child = entryContents.child(i);
      if (!child.hasClass("wp-block-heading") || !child.tagName().equals("h2")) {
        i += 1;
        continue;
      }

      MonthDay date = parseDate(child);
      Element dataNode = entryContents.child(i + 1);
      while(!dataNode.hasClass("wp-block-heading")) {
        List<MovieShowing> movieShowings = new ArrayList<>();

        Elements timeElems = new Elements(dataNode.getElementsByTag("strong").stream()
            .filter((s) -> s.text() != null && s.text().contains(":")).toList());
        int textNodeInd = 0;
        for (Element timeElem : timeElems) {
          if (timeElem.text().matches("\\(\\d{2,}mm\\)") || timeElem.text().matches("\\(Digital\\)")) {
            continue;
          }
          int j = timeElem.siblingIndex();
          String time = dataNode.childNodes().get(j).childNode(0).toString();
          String title = dataNode.childNodes().get(j + 2) instanceof TextNode
              ? dataNode.childNodes().get(j + 2).toString().strip()
              : dataNode.childNodes().get(j + 2).childNode(0).toString().strip();
          List<TextNode> textNodes = dataNode.textNodes().stream()
              .filter((tn) -> !tn.isBlank() && !tn.text().equals(" – ") && !tn.text().equals(" & "))
              .toList();
          String director = textNodes.get(textNodeInd).text();
          String location = textNodes.get(textNodeInd + 1).text().replaceAll(" – Part of ", "");
          movieShowings.add(MovieShowing.builder().time(time).title(title)
              .director(director).location(location).build());
          textNodeInd += 2;
        }

        if (!movieShowings.isEmpty()) {
          showings.put(date, movieShowings);
        }

        i += 1;
        if (i + 2 >= entryContents.children().size()) {
          break;
        }
        dataNode = entryContents.child(i + 2);
      }
    }
    return showings;
  }

  private boolean doesShowingContainKeywords(MovieShowing showing) {
    for (String keyword : keywords) {
      if (showing.getTitle().toLowerCase().replaceAll("[^\\w\s]", "").contains(keyword.toLowerCase())) {
        return true;
      }
    }
    return false;
  }

  private MonthDay parseDate(Element elem) {
    Elements children = elem.child(0).children();

    Element temp = children.get(0);
    if (children.toString().contains("Monday") || children.toString().contains("Tuesday") || children.toString().contains("Wednesday") || children.toString().contains("Thursday") || children.toString().contains("Friday") || children.toString().contains("Saturday") || children.toString().contains("Sunday")) {
      temp = children.get(1);
    }

    while (temp.textNodes().isEmpty()) {
      temp = temp.child(0);
    }
    String month = temp.text();
    int day = Integer.parseInt(elem.child(0).textNodes().get(1).toString().replaceAll("[^0-9]", ""));

    return MonthDay.of(Month.valueOf(month.toUpperCase()), day);
  }

  private void writeToFile(Map<MonthDay, List<MovieShowing>> showings) {
    StringBuilder csv = new StringBuilder();
    for (MonthDay date : showings.keySet().stream().sorted().toList()) {
      List<MovieShowing> showingsForDay = showings.get(date).stream()
          .sorted(Comparator.comparing(MovieShowing::getTime)).toList();

      if (showingsForDay.isEmpty()) { continue; }

      String dateString = date.format(DateTimeFormatter.ofPattern("MM-dd"));
      for (MovieShowing showing : showingsForDay) {
        csv.append(dateString).append(DELIMITER).append(showing.getTitle()).append(DELIMITER)
            .append(showing.getDirector()).append(DELIMITER).append(showing.getLocation()).append(DELIMITER)
            .append(showing.getTime()).append("\n");
      }
    }

    try {
      Instant now = Instant.now();
      DateFormat dateFormat = DateFormat.getDateTimeInstance();
      BufferedWriter writer = new BufferedWriter(
          new FileWriter(String.format("history/%s.csv", dateFormat.format(Date.from(now)))));
      writer.write(csv.toString());
      writer.close();
      log.info("Successfully wrote {}.csv to file", dateFormat.format(Date.from(now)));
    } catch (IOException e) {
      log.error("Writing to file failed");
    }
  }

  private Map<MonthDay, List<MovieShowing>> getMostRecentParsing() {
    File file = new File("history/");
    String[] files = file.list();
    if (files == null || files.length == 0) {
      return Collections.emptyMap();
    }
    DateFormat dateFormat = DateFormat.getDateTimeInstance();

    Map<MonthDay, List<MovieShowing>> result = new HashMap<>();
    try {
      List<String> sortedFiles = Stream.of(files)
          .sorted(Comparator.<String, Date>comparing(k -> {
                try {
                  return dateFormat.parse(k.replaceAll(".csv", ""));
                } catch (ParseException e) {
                  throw new RuntimeException(e);
                }
              })
              .reversed())
          .toList();

      File myObj = new File("history/" + sortedFiles.get(0));
      Scanner myReader = new Scanner(myObj);
      while (myReader.hasNextLine()) {
        String data = myReader.nextLine();
        String[] tokens = data.split(DELIMITER);
        MonthDay date = MonthDay.parse(tokens[0], DateTimeFormatter.ofPattern("MM-dd"));
        MovieShowing showing = MovieShowing.builder()
            .title(tokens[1]).director(tokens[2]).location(tokens[3]).time(tokens[4])
            .build();

        if (result.containsKey(date)) {
          result.get(date).add(showing);
        } else {
          List<MovieShowing> initialShowings = new ArrayList<>();
          initialShowings.add(showing);
          result.put(date, initialShowings);
        }
      }
      myReader.close();
    } catch (FileNotFoundException e) {
      log.error("Error occurred while parsing csv", e);
    }

    return result;
  }

  private void sendEmail(Map<MonthDay, List<MovieShowing>> diffs) {
    StringBuilder str = new StringBuilder();
    str.append("<h1>NEW FILM SHOWINGS DETECTED:</h1>\n\n");

    for (MonthDay monthDay : diffs.keySet()) {
      str.append("<h2>").append(monthDay.format(DateTimeFormatter.ofPattern("MM-dd"))).append("</h2>");
      for (MovieShowing showing : diffs.get(monthDay)) {
        str.append("<p>").append(showing.toString()).append("</p>");
      }
      str.append("\n");
    }

    str.append("Source: https://analogfilmnyc.org/upcoming-screenings/");

    sendGridApiProxy.sendEmail(destinationEmail, "New Film Showings Found", str.toString());
  }

  @Getter
  @Builder
  @EqualsAndHashCode
  private static class MovieShowing {

    private final String title;

    private final String director;

    private final String location;

    private final String time;

    @Override
    public String toString() {
      return title + director + " | " + location + " @ " + time;
    }

  }

}
