package com.mapploy.backend.sync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobCategoryTests {

    @Test
    void classifiesRolesAcrossDifferentInterests() {
        assertThat(PersonioFeedParser.categoryFor("Senior Java Developer", "Development")).isEqualTo("IT & Software");
        assertThat(PersonioFeedParser.categoryFor("Fachkraft für Lagerlogistik", "Operations")).isEqualTo("Operations & Logistics");
        assertThat(PersonioFeedParser.categoryFor("Personalreferent Recruiting", "Human Resources")).isEqualTo("People & HR");
        assertThat(PersonioFeedParser.categoryFor("Sales Manager", "Sales & Marketing")).isEqualTo("Sales & Customer");
        assertThat(PersonioFeedParser.categoryFor("Buchhalter", "Finance")).isEqualTo("Finance & Administration");
        assertThat(PersonioFeedParser.categoryFor("Werkstudent Mechanische Konstruktion", "Engineering")).isEqualTo("Engineering & Technical");
        assertThat(PersonioFeedParser.categoryFor("Produktmanager", "Design & Components")).isEqualTo("Project & Product");
    }
}
