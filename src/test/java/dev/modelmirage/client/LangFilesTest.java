package dev.modelmirage.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Keeps both languages complete and in sync with the keys the code and the HUD module definitions use. */
class LangFilesTest {
	/** Keys passed to Lang.tr, Component.translatable or a KeyMapping, and key constants such as CatalogService.ERROR_UNAVAILABLE. */
	private static final Pattern KEY_LITERAL = Pattern.compile(
			"(?:\\b(?:tr|translatable)\\(\\s*|new KeyMapping\\(\\s*|static final String [A-Z_]+ = )\"((?:key\\.)?modelmirage\\.[a-zA-Z0-9_.]*)\"");
	private static final Pattern FORMAT = Pattern.compile("%(?:\\d+\\$)?[sd]");
	private static Map<String, String> english, russian;
	private static Set<String> sourceKeys, moduleKeys;

	@BeforeAll
	static void load() throws IOException {
		english = lang("en_us");
		russian = lang("ru_ru");
		sourceKeys = new TreeSet<>();
		String sourceDir = System.getProperty("modelmirage.sourceDir");
		assumeTrue(sourceDir != null, "run through Gradle, which passes modelmirage.sourceDir");
		try (Stream<Path> files = Files.walk(Path.of(sourceDir))) {
			for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
				Matcher matcher = KEY_LITERAL.matcher(Files.readString(file));
				while (matcher.find()) sourceKeys.add(matcher.group(1));
			}
		}
		moduleKeys = new TreeSet<>();
		for (JsonElement element : resource("/assets/modelmirage/visuals/modules.json").getAsJsonArray()) {
			JsonObject module = element.getAsJsonObject();
			String moduleKey = "modelmirage.module." + module.get("id").getAsString();
			moduleKeys.add(moduleKey);
			for (JsonElement settingElement : module.getAsJsonArray("settings")) {
				JsonObject setting = settingElement.getAsJsonObject();
				// Orders are dragged on the HUD and never shown as a named row.
				if (setting.get("type").getAsString().equals("order")) continue;
				String settingKey = moduleKey + "." + setting.get("id").getAsString();
				moduleKeys.add(settingKey);
				JsonArray options = setting.getAsJsonArray("options");
				if (options != null) for (JsonElement option : options) moduleKeys.add(settingKey + "." + option.getAsJsonObject().get("id").getAsString());
			}
		}
	}

	private static JsonElement resource(String path) throws IOException {
		try (InputStream stream = LangFilesTest.class.getResourceAsStream(path)) {
			if (stream == null) throw new IOException("Missing resource " + path);
			return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
		}
	}

	private static Map<String, String> lang(String code) throws IOException {
		Map<String, String> result = new LinkedHashMap<>();
		resource("/assets/modelmirage/lang/" + code + ".json").getAsJsonObject().entrySet()
				.forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsString()));
		return result;
	}

	@Test
	void languagesHaveTheSameKeys() {
		assertEquals(new TreeSet<>(english.keySet()), new TreeSet<>(russian.keySet()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"en_us", "ru_ru"})
	void noValueIsBlank(String code) throws IOException {
		lang(code).forEach((key, value) -> assertFalse(value.isBlank(), code + " " + key));
	}

	@Test
	void translationsKeepTheSameFormatArguments() {
		english.forEach((key, value) -> {
			if (russian.containsKey(key)) assertEquals(arguments(value), arguments(russian.get(key)), key);
		});
	}

	private static List<String> arguments(String value) {
		return FORMAT.matcher(value).results().map(java.util.regex.MatchResult::group).sorted().toList();
	}

	@Test
	void englishHasNoCyrillicLeftovers() {
		english.forEach((key, value) -> assertFalse(value.codePoints().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC), key));
	}

	@Test
	void everyKeyInTheCodeIsTranslated() {
		assertFalse(sourceKeys.isEmpty(), "source scan found no keys");
		for (String key : sourceKeys) {
			// Prefixes completed at runtime, e.g. "modelmirage.module." + id, are covered by the module test.
			if (key.endsWith(".")) continue;
			assertTrue(english.containsKey(key), "missing translation " + key);
		}
	}

	@Test
	void everyHudModuleSettingAndOptionIsTranslated() {
		for (String key : moduleKeys) assertTrue(english.containsKey(key), "missing translation " + key);
	}

	@Test
	void noTranslationIsUnused() {
		for (String key : english.keySet()) {
			// Fabric derives the key binding category from the registered Identifier modelmirage:controls.
			if (key.equals("key.category.modelmirage.controls")) continue;
			assertTrue(sourceKeys.contains(key) || moduleKeys.contains(key), "unused translation " + key);
		}
	}

	/** The menu draws text with its own MSDF font; a character it lacks shows as a broken glyph. */
	@ParameterizedTest
	@ValueSource(strings = {"en_us", "ru_ru"})
	void everyCharacterExistsInTheUiFont(String code) throws IOException {
		java.util.Set<Integer> glyphs = new java.util.HashSet<>();
		for (JsonElement glyph : resource("/assets/modelmirage/ui/fonts/semibold.json").getAsJsonObject().getAsJsonArray("glyphs")) {
			if (glyph.getAsJsonObject().has("unicode")) glyphs.add(glyph.getAsJsonObject().get("unicode").getAsInt());
		}
		lang(code).forEach((key, value) -> value.codePoints().filter(c -> c != ' ' && !glyphs.contains(c))
				.forEach(c -> org.junit.jupiter.api.Assertions.fail(key + " uses " + Character.toString(c) + " which the UI font lacks")));
	}

	@ParameterizedTest
	@ValueSource(strings = {"en_us", "ru_ru"})
	void percentSignsAreEscaped(String code) throws IOException {
		lang(code).forEach((key, value) -> assertFalse(value.replace("%%", "").matches(".*%(?![sd]|\\d+\\$).*"), key + " has a lone % that String.format rejects"));
	}
}
