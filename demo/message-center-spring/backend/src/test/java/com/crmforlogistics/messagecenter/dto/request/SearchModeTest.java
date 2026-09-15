package com.crmforlogistics.messagecenter.dto.request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchModeTest {

    @Test
    void defaultsToContactWhenAbsentOrBlank() {
        assertThat(SearchMode.parse(null)).isEqualTo(SearchMode.CONTACT);
        assertThat(SearchMode.parse("")).isEqualTo(SearchMode.CONTACT);
        assertThat(SearchMode.parse("   ")).isEqualTo(SearchMode.CONTACT);
    }

    @Test
    void parsesBothKnownValuesCaseInsensitively() {
        assertThat(SearchMode.parse("contact")).isEqualTo(SearchMode.CONTACT);
        assertThat(SearchMode.parse("CONTACT")).isEqualTo(SearchMode.CONTACT);
        assertThat(SearchMode.parse(" tag ")).isEqualTo(SearchMode.TAG);
        assertThat(SearchMode.parse("TAG")).isEqualTo(SearchMode.TAG);
        assertThat(SearchMode.parse("TAG").isTag()).isTrue();
        assertThat(SearchMode.parse("contact").isTag()).isFalse();
    }

    @Test
    void rejectsUnknownValuesInsteadOfSilentlyFallingBackToContact() {
        assertThatThrownBy(() -> SearchMode.parse("tags"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CONTACT_SEARCH_MODE_INVALID");
        assertThatThrownBy(() -> SearchMode.parse("label"))
                .hasMessage("CONTACT_SEARCH_MODE_INVALID");
    }
}
