package com.dis.fshipbot;

import com.dis.fshipbot.model.PromptSection;
import com.dis.fshipbot.service.PromptService;
import com.dis.fshipbot.util.PromptDefaults;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rendered admin Prompts page.
 *
 * <p>This is a template test, not a service test, because the failures it guards
 * against are template-level and invisible from Java: the page rendered
 * completely empty (Thymeleaf evaluates {@code th:if} before {@code th:with}, so
 * the group lookup was always null), and the JS context path was silently wrong
 * because the inline expression was never substituted. Both produced a page that
 * looked plausible and did nothing.
 */
@SpringBootTest(properties = {
        "app.ingestion.run-on-startup=false",
        "spring.datasource.url=jdbc:h2:mem:admintpl;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none"
})
@AutoConfigureMockMvc
class AdminPromptsPageTest {

    // The pgvector store cannot start on H2; these are not under test here.
    @MockBean
    private EmbeddingStore<TextSegment> embeddingStore;

    @MockBean
    private EmbeddingModel embeddingModel;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PromptService promptService;

    private MockHttpSession adminSession;

    @BeforeEach
    void setUp() {
        promptService.reload();
        adminSession = new MockHttpSession();
        adminSession.setAttribute(com.dis.fshipbot.config.PageAccessInterceptor.SESSION_KEY, true);
    }

    private String renderPage() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/prompts").session(adminSession))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private static String attr(String key) {
        return "data-key=\"" + key + "\"";
    }

    @Test
    void pageRendersEveryPromptCard() throws Exception {
        String html = renderPage();
        for (PromptDefaults.PromptDef def : PromptDefaults.all()) {
            Assertions.assertTrue(html.contains("data-key=\"" + def.getKey() + "\""),
                    "prompt " + def.getKey() + " is missing from the page — the grid rendered empty");
        }
    }

    @Test
    void everyPromptOffersAnEditButton() throws Exception {
        String html = renderPage();
        for (PromptDefaults.PromptDef def : PromptDefaults.all()) {
            Assertions.assertTrue(html.contains(attr(def.getKey())),
                    def.getKey() + " has no card");
            // Attribute order within a tag is not guaranteed, so match the tag
            // as a whole rather than assuming data-key precedes onclick.
            Assertions.assertTrue(editButtonFor(html, def.getKey()),
                    def.getKey() + " has no Edit button");
        }
    }

    /** True when some {@code <button>} carries this key and the openEditor handler. */
    private static boolean editButtonFor(String html, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<button[^>]*onclick=\"openEditor\\(this\\)\"[^>]*>")
                .matcher(html);
        while (m.find()) {
            if (m.group().contains("data-key=\"" + key + "\"")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void editIsTheOnlyCardAction() throws Exception {
        // The card exposes exactly one control. A stray handler would either do
        // nothing (the JS is gone) or silently reintroduce a removed action.
        String html = renderPage();
        for (String removed : new String[]{
                "copyPrompt", "viewPrompt", "resetPrompt", "resetAllPrompts"}) {
            Assertions.assertFalse(html.contains(removed + "("),
                    removed + " should have been removed from the prompts page");
        }
    }

    @Test
    void bulkActionsAreNotRendered() throws Exception {
        String html = renderPage();
        Assertions.assertFalse(html.contains("Reset all"),
                "the reset-all action should have been removed");
    }

    @Test
    void pageReportsWhenTheDatabaseIsReady() throws Exception {
        // The banner is how an admin learns that prompts are not editable yet.
        // H2 has no app_prompt table, so this test sees the NOT-editable state.
        String html = renderPage();
        Assertions.assertTrue(html.contains("Prompts are not editable right now"),
                "the page must warn when prompts are running on built-in defaults");
        Assertions.assertTrue(html.contains("app_prompt"),
                "the warning must name the table the admin has to seed");
    }

    @Test
    void saveEndpointIsPresent() throws Exception {
        // Edit is the only control, so the POST it calls is the only write path
        // the page depends on.
        String html = renderPage();
        Assertions.assertTrue(html.contains("ADMIN_API + '/prompts'"),
                "the editor must post to the prompts API");
        Assertions.assertTrue(html.contains("method: 'POST'"),
                "the save must be a POST");
    }

    @Test
    void everyGroupIsRenderedAsASection() throws Exception {
        // Assert on data-group, not the title: titles are HTML-escaped on render
        // ("Jev re-rank & grounding" becomes "... &amp; ..."), so a raw substring
        // check would miss a group that rendered perfectly well.
        String html = renderPage();
        for (PromptSection section : promptService.listSections()) {
            Assertions.assertTrue(html.contains("data-group=\"" + section.getId() + "\""),
                    "group section missing: " + section.getId());
        }
        Assertions.assertEquals(PromptDefaults.groups().size(),
                countOccurrences(html, "class=\"prompt-section\""),
                "every registry group must render a section");
    }

    @Test
    void everyGroupCarriesTitleAndDescription() throws Exception {
        for (PromptSection section : promptService.listSections()) {
            Assertions.assertFalse(section.getTitle().isBlank(),
                    section.getId() + " has no title");
            Assertions.assertFalse(section.getDescription().isBlank(),
                    section.getId() + " has no description");
        }
    }

    @Test
    void groupsRenderInRegistryOrder() throws Exception {
        String html = renderPage();
        int previous = -1;
        for (String group : PromptDefaults.groups()) {
            int at = html.indexOf("data-group=\"" + group + "\"");
            Assertions.assertTrue(at >= 0, "missing group: " + group);
            Assertions.assertTrue(at > previous, group + " is out of registry order");
            previous = at;
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    @Test
    void promptTextIsNotRenderedAsUnescapedHtml() throws Exception {
        // Prompt body text belongs in the API and the editor textarea, never in
        // the page as live HTML.
        String html = renderPage();
        Assertions.assertFalse(html.contains("<th>You are Friendship AI"),
                "prompt body text must not be injected as HTML");
    }

    @Test
    void contextPathIsPublishedForJavascript() throws Exception {
        // Every admin fetch is built from this meta tag. If it is missing, or the
        // JS falls back to a literal, logout and every save hit the wrong path.
        String html = renderPage();
        Assertions.assertTrue(html.contains("meta name=\"admin-ctx\""),
                "the admin-ctx meta tag is missing");
        Assertions.assertTrue(html.contains("querySelector('meta[name=\"admin-ctx\"]')"),
                "the JS must read the context path from the DOM, not an inline literal");
        Assertions.assertFalse(html.contains("'/fshipbot/'"),
                "a hard-coded fallback context path has crept back in");
    }

    @Test
    void loginRedirectsToThePromptsPage() throws Exception {
        mockMvc.perform(post("/admin/login")
                        .param("key", com.dis.fshipbot.controller.HomeController.todayKey()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/prompts"));
    }

    @Test
    void badKeyStaysOnTheLoginPage() throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/login").param("key", "00000000"))
                .andExpect(status().isOk())
                .andReturn();
        Assertions.assertTrue(result.getResponse().getContentAsString().contains("Invalid key"));
    }

    @Test
    void promptsPageRequiresASession() throws Exception {
        // MockMvc runs with an empty context path, so the redirect target is
        // "/admin/login"; a real deployment prefixes it at runtime.
        mockMvc.perform(get("/admin/prompts"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void apiRejectsAnUnknownPromptKey() throws Exception {
        mockMvc.perform(post("/admin/api/prompts")
                        .session(adminSession)
                        .contentType("application/json")
                        .content("{\"key\":\"prompt.invented\",\"content\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void apiRejectsARequestWithNoKey() throws Exception {
        mockMvc.perform(post("/admin/api/prompts")
                        .session(adminSession)
                        .contentType("application/json")
                        .content("{\"content\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void apiPromptsPayloadIsJson() throws Exception {
        mockMvc.perform(get("/admin/api/prompts").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    void sectionsExposePromptsForTheApi() {
        List<PromptSection> sections = promptService.listSections();
        int total = sections.stream().mapToInt(s -> s.getPrompts().size()).sum();
        Assertions.assertEquals(PromptDefaults.all().size(), total);
    }
}
