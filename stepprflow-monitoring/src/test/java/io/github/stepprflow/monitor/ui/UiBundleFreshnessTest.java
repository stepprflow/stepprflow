package io.github.stepprflow.monitor.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards against shipping a stale SPA bundle. The frontend lives in the
 * separate {@code stepprflow-ui} module, and for a long time nothing in the
 * Maven build rebuilt it — releases silently packaged whatever pre-built
 * {@code static/} happened to be committed, which drifted several features
 * behind the source (the SF-5 OIDC login flow never reached the published jar).
 *
 * <p>This test reads the UI bundle actually packaged on the classpath and
 * asserts it carries a marker from the current frontend source (the OIDC login
 * redirect). It fails if the frontend was not rebuilt as part of the Maven
 * build, turning "stale bundle shipped" from a silent release bug into a red
 * test.</p>
 */
@DisplayName("Packaged UI bundle freshness")
class UiBundleFreshnessTest {

    @Test
    @DisplayName("The packaged SPA bundle carries the current frontend (OIDC login flow)")
    void packagedBundleContainsOidcLoginFlow() throws Exception {
        final URL indexUrl = getClass().getResource("/static/index.html");
        assertThat(indexUrl)
                .as("the UI shell (static/index.html) must be packaged into the jar")
                .isNotNull();

        final String index =
                Files.readString(Path.of(indexUrl.toURI()), StandardCharsets.UTF_8);
        final Matcher m = Pattern.compile("assets/(index-[A-Za-z0-9_-]+\\.js)").matcher(index);
        assertThat(m.find())
                .as("index.html must reference a built JS bundle under assets/")
                .isTrue();

        final URL jsUrl = getClass().getResource("/static/assets/" + m.group(1));
        assertThat(jsUrl)
                .as("the referenced JS bundle %s must be packaged", m.group(1))
                .isNotNull();

        final String js = Files.readString(Path.of(jsUrl.toURI()), StandardCharsets.UTF_8);
        assertThat(js)
                .as("the packaged bundle must be built from the current source — it "
                        + "should contain the OIDC login redirect. A missing marker means "
                        + "the frontend was not rebuilt during the Maven build (stale bundle).")
                .contains("oauth2/authorization");
    }
}
