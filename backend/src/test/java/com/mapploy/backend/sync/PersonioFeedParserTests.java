package com.mapploy.backend.sync;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersonioFeedParserTests {

    @Test
    void parsesOfficialFeedFieldsAndBuildsStableMapCoordinates() {
        String xml = """
                <workzag-jobs>
                  <position>
                    <id>42</id>
                    <name><![CDATA[Java Developer (m/f/d)]]></name>
                    <subcompany>Example GmbH</subcompany>
                    <office>Hamburg / Remote</office>
                    <department>IT</department>
                    <employmentType>permanent</employmentType>
                    <seniority>entry-level</seniority>
                    <jobDescriptions>
                      <jobDescription>
                        <name>Your tasks</name>
                        <value><![CDATA[<p>Build APIs with <strong>Spring Boot</strong>.</p>]]></value>
                      </jobDescription>
                    </jobDescriptions>
                  </position>
                </workzag-jobs>
                """;
        FeedCatalog.Feed feed = new FeedCatalog.Feed("example", "Example", "https://example.test/feed", "https://example.test/job/");

        List<ParsedJob> jobs = new PersonioFeedParser().parse(xml, feed);

        assertThat(jobs).hasSize(1);
        ParsedJob job = jobs.getFirst();
        assertThat(job.id()).isEqualTo("example:42");
        assertThat(job.rawDescription()).contains("[Your tasks]", "Build APIs with Spring Boot.");
        assertThat(job.category()).isEqualTo("IT & Software");
        assertThat(job.longitude()).isBetween(9.98, 10.01);
        assertThat(job.latitude()).isBetween(53.54, 53.56);
        assertThat(job.contentHash()).hasSize(64);
    }
}
