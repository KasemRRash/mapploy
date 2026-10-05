package com.mapploy.backend.sync;

import java.util.List;

public final class FeedCatalog {
    private FeedCatalog() {
    }

    public static final List<Feed> FEEDS = List.of(
            new Feed("hmmh", "hmmh multimediahaus AG", "https://hmmh.jobs.personio.de/xml?language=de", "https://hmmh.jobs.personio.de/job/"),
            new Feed("governikus", "Governikus Software GmbH", "https://governikus.jobs.personio.de/xml?language=de", "https://governikus.jobs.personio.de/job/"),
            new Feed("we4it-group", "WE4IT Group", "https://we4it-group.jobs.personio.de/xml?language=de", "https://we4it-group.jobs.personio.de/job/"),
            new Feed("hoppe-marine", "Hoppe Marine GmbH", "https://hoppe-marine-gmbh.jobs.personio.de/xml?language=de", "https://hoppe-marine-gmbh.jobs.personio.de/job/"),
            new Feed("spaceteams", "Spaceteams GmbH", "https://spaceteams.jobs.personio.de/xml?language=de", "https://spaceteams.jobs.personio.de/job/"),
            new Feed("ip-dynamics", "IP Dynamics GmbH", "https://ip-dynamics-gmbh.jobs.personio.de/xml?language=de", "https://ip-dynamics-gmbh.jobs.personio.de/job/"),
            new Feed("sog", "SOG Business-Software GmbH", "https://personio-sog.jobs.personio.de/xml?language=de", "https://personio-sog.jobs.personio.de/job/")
    );

    public record Feed(String key, String name, String url, String jobBaseUrl) {
    }
}
