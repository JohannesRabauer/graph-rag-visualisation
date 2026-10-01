package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The community legend keeps to one line however many Communities a corpus
 * has: chips are ordered largest first, the ones that do not fit are hidden
 * and counted on a trailing "+N · All communities" button, which opens the
 * detail panel's list of every Community. A row there opens that
 * Community's detail exactly as tapping its hull does.
 *
 * <p>The demo dataset initialises the page; the canvas is then re-initialised
 * and filled through {@code GraphCanvas.addCommunity} (the API the SSE and
 * graph-endpoint paths drive), so the Communities under test are fixed.
 */
class CommunityLegendUiTest extends UiTestSupport {

    private static final Pattern OPEN_CLASS = Pattern.compile(".*\\bis-open\\b.*");
    private static final double READY_TIMEOUT_MS = 60000;
    private static final int MANY = 40;

    private void loadDemoDatasetAndResetCanvas() {
        page.navigate(baseUrl() + "/");
        page.locator("#demo-dataset-button").click();
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(READY_TIMEOUT_MS));
        page.evaluate("() => window.GraphCanvas.init()");
    }

    /** Adds {@code count} Communities; community {@code i} has {@code (i * 7) % 11 + 1} members. */
    private void addCommunities(int count) {
        page.evaluate("count => {"
                + "  const gc = window.GraphCanvas;"
                + "  for (let i = 0; i < count; i++) {"
                + "    const size = (i * 7) % 11 + 1;"
                + "    const members = [];"
                + "    for (let m = 0; m < size; m++) {"
                + "      const id = 'c' + i + '-m' + m + '::concept';"
                + "      gc.addEntity(id, 'Member ' + i + '.' + m, 'concept');"
                + "      members.push(id);"
                + "    }"
                + "    gc.addCommunity('legend-community-' + i, 'Summary of community ' + i, members,"
                + "        'Community number ' + String(i).padStart(2, '0'));"
                + "  }"
                + "}", count);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> visibleChips() {
        return (List<Map<String, Object>>) page.evaluate("() => Array.from("
                + "  document.querySelectorAll('#graph-legend .graph-legend-item'))"
                + "  .filter(chip => !chip.hidden)"
                + "  .map(chip => ({ id: chip.dataset.communityId, top: chip.offsetTop,"
                + "    height: chip.offsetHeight, members: Number(chip.dataset.memberCount) }))");
    }

    private int hiddenChipCount() {
        return ((Number) page.evaluate("() => document.querySelectorAll("
                + "  '#graph-legend .graph-legend-item[hidden]').length")).intValue();
    }

    private void assertSingleLine() {
        // Lets the legend's ResizeObserver re-fit after a width change.
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
        List<Map<String, Object>> visible = visibleChips();
        org.assertj.core.api.Assertions.assertThat(visible).isNotEmpty();
        int firstTop = ((Number) visible.get(0).get("top")).intValue();
        int chipHeight = ((Number) visible.get(0).get("height")).intValue();
        visible.forEach(chip -> org.assertj.core.api.Assertions.assertThat(((Number) chip.get("top")).intValue())
                .as("chip %s on the first line", chip.get("id"))
                .isEqualTo(firstTop));
        double legendHeight = ((Number) page.evaluate(
                "() => document.getElementById('graph-legend').getBoundingClientRect().height")).doubleValue();
        org.assertj.core.api.Assertions.assertThat(legendHeight).isLessThanOrEqualTo(chipHeight + 8);
    }

    @Test
    void manyCommunitiesKeepTheLegendOnOneLineAndAllCommunitiesListsAndOpensEachOne() {
        loadDemoDatasetAndResetCanvas();
        addCommunities(MANY);

        Locator legend = page.locator("#graph-legend");
        assertThat(legend).isVisible();
        assertSingleLine();

        int hidden = hiddenChipCount();
        org.assertj.core.api.Assertions.assertThat(hidden).isPositive();
        Locator more = page.locator("#graph-legend .graph-legend-more");
        assertThat(more).isVisible();
        assertThat(more).hasText("+" + hidden + " · All communities");

        // The visible chips are the largest Communities, in size order.
        List<Map<String, Object>> visible = visibleChips();
        org.assertj.core.api.Assertions.assertThat(visible.size() + hidden).isEqualTo(MANY);
        List<Integer> allSizes = new ArrayList<>();
        for (int i = 0; i < MANY; i++) {
            allSizes.add((i * 7) % 11 + 1);
        }
        allSizes.sort(Comparator.reverseOrder());
        List<Integer> visibleSizes = visible.stream().map(chip -> ((Number) chip.get("members")).intValue()).toList();
        org.assertj.core.api.Assertions.assertThat(visibleSizes).isEqualTo(allSizes.subList(0, visible.size()));

        // "All communities" opens the panel listing every Community, largest first.
        more.click();
        Locator panel = page.locator("#entity-detail-panel");
        assertThat(panel).hasClass(OPEN_CLASS);
        assertThat(page.locator("#entity-detail-eyebrow")).hasText("Communities");
        assertThat(page.locator("#entity-detail-name")).hasText(MANY + " communities");
        Locator rows = page.locator("#entity-detail-communities .node-detail-community-row");
        assertThat(rows).hasCount(MANY);
        List<String> counts = rows.locator(".node-detail-community-count").allTextContents();
        List<String> expectedCounts = allSizes.stream()
                .map(size -> size + (size == 1 ? " entity" : " entities"))
                .toList();
        org.assertj.core.api.Assertions.assertThat(counts).isEqualTo(expectedCounts);
        assertThat(rows.first().locator(".node-detail-community-name")).hasText(Pattern.compile("Community number \\d\\d"));

        // The panel narrows the legend; it re-fits and still keeps one line.
        assertSingleLine();
        assertThat(more).hasText("+" + hiddenChipCount() + " · All communities");
        org.assertj.core.api.Assertions.assertThat(hiddenChipCount()).isGreaterThanOrEqualTo(hidden);

        // A row opens that Community's detail, as tapping its hull does.
        Locator lastRow = rows.last();
        String communityId = lastRow.getAttribute("data-community-id");
        String name = lastRow.locator(".node-detail-community-name").textContent();
        lastRow.click();
        assertThat(panel).hasClass(OPEN_CLASS);
        assertThat(page.locator("#entity-detail-eyebrow")).hasText("Community");
        assertThat(page.locator("#entity-detail-name")).hasText(name);
        assertThat(page.locator("#entity-detail-communities-section")).isHidden();
        org.assertj.core.api.Assertions.assertThat((Boolean) page.evaluate(
                "id => window.GraphCanvas.elementHasClass('community::' + id, 'community-focused')", communityId))
                .isTrue();

        // The close button works as before.
        page.locator("#entity-detail-close").click();
        assertThat(panel).not().hasClass(OPEN_CLASS);
    }

    @Test
    void aFewSmallCommunitiesAllFitAndTheButtonHasNoHiddenCount() {
        loadDemoDatasetAndResetCanvas();
        page.evaluate("() => {"
                + "  const gc = window.GraphCanvas;"
                + "  ['a', 'b', 'c'].forEach((key, index) => {"
                + "    const id = key + '::concept';"
                + "    gc.addEntity(id, key.toUpperCase(), 'concept');"
                + "    gc.addCommunity('small-' + key, 'Small ' + key, [id], 'Small ' + key);"
                + "  });"
                + "}");

        assertSingleLine();
        org.assertj.core.api.Assertions.assertThat(visibleChips()).hasSize(3);
        Locator more = page.locator("#graph-legend .graph-legend-more");
        assertThat(more).isVisible();
        assertThat(more).hasText("All communities");

        more.click();
        assertThat(page.locator("#entity-detail-name")).hasText("3 communities");
        assertThat(page.locator("#entity-detail-communities .node-detail-community-row")).hasCount(3);

        // A second click on the button closes the list again.
        more.click();
        assertThat(page.locator("#entity-detail-panel")).not().hasClass(OPEN_CLASS);
    }

    @Test
    void aSingleCommunityShowsNoAllCommunitiesButton() {
        loadDemoDatasetAndResetCanvas();
        page.evaluate("() => {"
                + "  window.GraphCanvas.addEntity('solo::concept', 'Solo', 'concept');"
                + "  window.GraphCanvas.addCommunity('solo', 'Solo summary', ['solo::concept'], 'Solo');"
                + "}");
        assertThat(page.locator("#graph-legend .graph-legend-item")).hasCount(1);
        assertThat(page.locator("#graph-legend .graph-legend-more")).hasCount(0);
    }
}
