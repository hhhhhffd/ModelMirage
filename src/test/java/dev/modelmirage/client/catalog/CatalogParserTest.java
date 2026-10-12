package dev.modelmirage.client.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogParserTest {
	private static final String SHA = "a".repeat(64);
	private static final int MIB = 1024 * 1024;

	static Stream<String> invalidFormats() {
		return Stream.of("", "Bad Format", "UPPER", "a/b", "x".repeat(65));
	}

	static Stream<String> badChecksums() {
		return Stream.of("", "A".repeat(64), "g".repeat(64), "a".repeat(63), "a".repeat(65), " " + "a".repeat(63), "a".repeat(62) + "\n");
	}

	private static JsonObject asset(String path, int bytes) {
		JsonObject asset = new JsonObject();
		asset.addProperty("path", path);
		asset.addProperty("sha256", SHA);
		asset.addProperty("bytes", bytes);
		return asset;
	}

	private static JsonObject entry(String id) {
		JsonObject entry = new JsonObject();
		entry.addProperty("id", id);
		entry.addProperty("name", "Model " + id);
		entry.addProperty("format", "java-item");
		entry.add("model", asset("models/" + id + ".json", 100));
		return entry;
	}

	private static JsonObject assets(int count, int bytes) {
		JsonObject object = new JsonObject();
		for (int i = 0; i < count; i++) object.add("modelmirage:t" + i, asset("textures/t" + i + ".png", bytes));
		return object;
	}

	private static byte[] index(int schema, JsonObject... entries) {
		JsonObject root = new JsonObject();
		root.addProperty("schema", schema);
		JsonArray models = new JsonArray();
		for (JsonObject entry : entries) models.add(entry);
		root.add("models", models);
		return root.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static List<CatalogModel> parse(JsonObject... entries) {
		return CatalogParser.parse(index(1, entries));
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2})
	void acceptsSupportedSchemas(int schema) {
		List<CatalogModel> models = CatalogParser.parse(index(schema, entry("axe")));
		assertEquals(2, models.size());
		assertEquals("axe", models.get(1).id());
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 3, -1})
	void rejectsUnsupportedSchemas(int schema) {
		assertThrows(IllegalArgumentException.class, () -> CatalogParser.parse(index(schema)));
	}

	@Test
	void builtinMascotIsAlwaysFirstEvenForEmptyCatalog() {
		List<CatalogModel> models = parse();
		assertEquals(1, models.size());
		assertTrue(models.get(0).builtin());
		assertEquals(CatalogModel.Kind.MASCOT, models.get(0).kind());
		assertEquals("builtin-mascot", models.get(0).id());
		assertEquals(CatalogModel.builtinMascot(), models.get(0));
	}

	@Test
	void appliesDefaults() {
		CatalogModel model = parse(entry("axe")).get(1);
		assertEquals(CatalogModel.Kind.ITEM, model.kind());
		assertEquals("", model.group());
		assertEquals(0xFF6573FF, model.color());
		assertTrue(model.items().isEmpty());
		assertTrue(model.textures().isEmpty());
		assertTrue(model.parents().isEmpty());
		assertEquals(new CatalogModel.Asset("models/axe.json", SHA, 100), model.model());
		assertEquals(Identifier.fromNamespaceAndPath("modelmirage", "catalog/axe"), model.modelId());
	}

	@Test
	void readsAllOptionalFields() {
		JsonObject entry = entry("sword");
		entry.addProperty("kind", "Player");
		entry.addProperty("group", "Weapons");
		entry.addProperty("color", "#1a2B3c");
		JsonArray items = new JsonArray();
		items.add("minecraft:diamond_sword");
		items.add("mace");
		entry.add("items", items);
		entry.add("textures", assets(2, 10));
		JsonObject parents = new JsonObject();
		parents.add("modelmirage:p", asset("parents/p.json", 5));
		entry.add("parents", parents);

		CatalogModel model = parse(entry).get(1);
		assertEquals(CatalogModel.Kind.PLAYER, model.kind());
		assertEquals("Weapons", model.group());
		assertEquals(0xFF1A2B3C, model.color());
		assertEquals(List.of(Identifier.withDefaultNamespace("diamond_sword"), Identifier.withDefaultNamespace("mace")), model.items());
		assertTrue(model.accepts(Identifier.withDefaultNamespace("mace")));
		assertTrue(!model.accepts(Identifier.withDefaultNamespace("stick")));
		assertEquals(2, model.textures().size());
		assertEquals(new CatalogModel.Asset("textures/t1.png", SHA, 10), model.textures().get(Identifier.fromNamespaceAndPath("modelmirage", "t1")));
		assertEquals(1, model.parents().size());
	}

	@Test
	void preservesCatalogOrderAfterMascot() {
		List<CatalogModel> models = parse(entry("b"), entry("a"), entry("c"));
		assertEquals(List.of("builtin-mascot", "b", "a", "c"), models.stream().map(CatalogModel::id).toList());
	}

	@Test
	void returnedListIsImmutable() {
		List<CatalogModel> models = parse(entry("a"));
		assertThrows(UnsupportedOperationException.class, () -> models.add(CatalogModel.builtinMascot()));
	}

	@Test
	void entryWithoutItemsAcceptsEveryItem() {
		assertTrue(parse(entry("a")).get(1).accepts(Identifier.withDefaultNamespace("anything")));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "Upper", "-lead", "_lead", "a b", "a.b", "a/b", "ü", "builtin-mascot"})
	void rejectsInvalidOrReservedIds(String id) {
		assertThrows(IllegalArgumentException.class, () -> parse(entry(id)));
	}

	@Test
	void idLengthLimitIsSixtyFourCharacters() {
		assertEquals(2, parse(entry("a".repeat(64))).size());
		assertThrows(IllegalArgumentException.class, () -> parse(entry("a".repeat(65))));
	}

	@Test
	void rejectsDuplicateIds() {
		assertThrows(IllegalArgumentException.class, () -> parse(entry("dup"), entry("dup")));
	}

	@Test
	void builtinMascotIdIsReserved() {
		assertThrows(IllegalArgumentException.class, () -> parse(entry(CatalogModel.BUILTIN_MASCOT)));
	}

	@Test
	void nameLimits() {
		JsonObject longest = entry("a");
		longest.addProperty("name", "n".repeat(128));
		assertEquals(2, parse(longest).size());
		for (String name : new String[] {"", "   ", "n".repeat(129)}) {
			JsonObject bad = entry("a");
			bad.addProperty("name", name);
			assertThrows(IllegalArgumentException.class, () -> parse(bad), "name length " + name.length());
		}
	}

	@ParameterizedTest
	@MethodSource("invalidFormats")
	void rejectsInvalidFormats(String format) {
		JsonObject bad = entry("a");
		bad.addProperty("format", format);
		assertThrows(IllegalArgumentException.class, () -> parse(bad));
	}

	@Test
	void groupLimitIsSixtyFourCharacters() {
		JsonObject ok = entry("a");
		ok.addProperty("group", "g".repeat(64));
		assertEquals("g".repeat(64), parse(ok).get(1).group());
		JsonObject bad = entry("b");
		bad.addProperty("group", "g".repeat(65));
		assertThrows(IllegalArgumentException.class, () -> parse(bad));
	}

	@ParameterizedTest
	@ValueSource(strings = {"item", "ITEM", "player", "mascot"})
	void acceptsKindsCaseInsensitively(String kind) {
		JsonObject entry = entry("a");
		entry.addProperty("kind", kind);
		assertEquals(kind.toUpperCase(java.util.Locale.ROOT), parse(entry).get(1).kind().name());
	}

	@ParameterizedTest
	@ValueSource(strings = {"block", "", "entity"})
	void rejectsUnknownKinds(String kind) {
		JsonObject entry = entry("a");
		entry.addProperty("kind", kind);
		assertThrows(IllegalArgumentException.class, () -> parse(entry));
	}

	@ParameterizedTest
	@ValueSource(strings = {"zzzzzz", "red", "", "#"})
	void rejectsMalformedColors(String color) {
		JsonObject entry = entry("a");
		entry.addProperty("color", color);
		assertThrows(RuntimeException.class, () -> parse(entry));
	}

	@Test
	void colorWithoutHashIsAcceptedAndAlwaysOpaque() {
		JsonObject entry = entry("a");
		entry.addProperty("color", "00ff00");
		assertEquals(0xFF00FF00, parse(entry).get(1).color());
	}

	@Test
	void itemTargetLimits() {
		JsonObject ok = entry("a");
		ok.add("items", names(4096));
		assertEquals(4096, parse(ok).get(1).items().size());
		JsonObject tooMany = entry("b");
		tooMany.add("items", names(4097));
		assertThrows(IllegalArgumentException.class, () -> parse(tooMany));
	}

	private static JsonArray names(int count) {
		JsonArray array = new JsonArray();
		for (int i = 0; i < count; i++) array.add("minecraft:item_" + i);
		return array;
	}

	@Test
	void rejectsInvalidItemIdentifier() {
		JsonObject entry = entry("a");
		JsonArray items = new JsonArray();
		items.add("Not An Identifier!");
		entry.add("items", items);
		assertThrows(RuntimeException.class, () -> parse(entry));
	}

	@Test
	void modelLimitIsFourThousandNinetySix() {
		JsonObject[] ok = new JsonObject[4096];
		for (int i = 0; i < ok.length; i++) ok[i] = entry("m" + i);
		assertEquals(4097, parse(ok).size());
		JsonObject[] tooMany = new JsonObject[4097];
		for (int i = 0; i < tooMany.length; i++) tooMany[i] = entry("m" + i);
		assertThrows(IllegalArgumentException.class, () -> parse(tooMany));
	}

	@Test
	void rejectsOversizedIndex() {
		byte[] small = index(1);
		byte[] padded = new byte[2 * MIB + 1];
		java.util.Arrays.fill(padded, (byte) ' ');
		System.arraycopy(small, 0, padded, 0, small.length);
		assertThrows(IllegalArgumentException.class, () -> CatalogParser.parse(padded));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "{}", "[]", "not json", "{\"schema\":1}", "{\"schema\":\"x\",\"models\":[]}", "{\"schema\":1,\"models\":{}}"})
	void rejectsMalformedDocuments(String json) {
		assertThrows(RuntimeException.class, () -> CatalogParser.parse(json.getBytes(StandardCharsets.UTF_8)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"id", "name", "format", "model"})
	void rejectsMissingRequiredFields(String field) {
		JsonObject entry = entry("a");
		entry.remove(field);
		assertThrows(RuntimeException.class, () -> parse(entry));
	}

	@ParameterizedTest
	@ValueSource(strings = {"models/a.json", "a", "A_b-c.1/d.e", "models/v1.2/a.json", "models/.hidden", "a..b"})
	void acceptsSafeAssetPaths(String path) {
		JsonObject entry = entry("a");
		entry.add("model", asset(path, 1));
		assertEquals(path, parse(entry).get(1).model().path());
	}

	@ParameterizedTest
	@ValueSource(strings = {"../x", "..", "a/../b", "a/..", "./a", "a/./b", "/abs", "/etc/passwd", "a//b", "a\\b", "..\\x",
			"a b", "a?b", "a%2e%2e/b", "https://evil/x", "ü.json", "", "a:b"})
	void rejectsUnsafeAssetPaths(String path) {
		JsonObject entry = entry("a");
		entry.add("model", asset(path, 1));
		assertThrows(IllegalArgumentException.class, () -> parse(entry));
	}

	@ParameterizedTest
	@ValueSource(strings = {"models/../x", "textures/../../x"})
	void pathTraversalIsRejectedInDependenciesToo(String path) {
		JsonObject entry = entry("a");
		JsonObject textures = new JsonObject();
		textures.add("modelmirage:t", asset(path, 1));
		entry.add("textures", textures);
		assertThrows(IllegalArgumentException.class, () -> parse(entry));
		entry.remove("textures");
		JsonObject parents = new JsonObject();
		parents.add("modelmirage:p", asset(path, 1));
		entry.add("parents", parents);
		assertThrows(IllegalArgumentException.class, () -> parse(entry));
	}

	@ParameterizedTest
	@MethodSource("badChecksums")
	void rejectsBadChecksums(String sha) {
		JsonObject entry = entry("a");
		JsonObject model = asset("m.json", 1);
		model.addProperty("sha256", sha);
		entry.add("model", model);
		assertThrows(IllegalArgumentException.class, () -> parse(entry));
	}

	@Test
	void acceptsAllHexDigitsInChecksum() {
		JsonObject entry = entry("a");
		JsonObject model = asset("m.json", 1);
		model.addProperty("sha256", "0123456789abcdef".repeat(4));
		entry.add("model", model);
		assertEquals("0123456789abcdef".repeat(4), parse(entry).get(1).model().sha256());
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1, 16L * MIB + 1, Integer.MAX_VALUE, 1L << 32})
	void rejectsModelSizesOutOfRange(long bytes) {
		JsonObject entry = entry("a");
		JsonObject model = asset("m.json", 1);
		model.addProperty("bytes", bytes);
		entry.add("model", model);
		assertThrows(IllegalArgumentException.class, () -> parse(entry));
	}

	@Test
	void modelSizeBoundaries() {
		for (int bytes : new int[] {1, 16 * MIB}) {
			JsonObject entry = entry("a");
			entry.add("model", asset("m.json", bytes));
			assertEquals(bytes, parse(entry).get(1).model().bytes());
		}
	}

	@Test
	void textureSizeLimitIsSixteenMiBEach() {
		JsonObject ok = entry("a");
		ok.add("model", asset("m.json", 1));
		ok.add("textures", assets(1, 16 * MIB));
		assertEquals(16 * MIB, parse(ok).get(1).textures().values().iterator().next().bytes());
		JsonObject bad = entry("b");
		bad.add("textures", assets(1, 16 * MIB + 1));
		assertThrows(IllegalArgumentException.class, () -> parse(bad));
	}

	@Test
	void parentSizeLimitIsFourMiBEach() {
		JsonObject ok = entry("a");
		JsonObject parents = new JsonObject();
		parents.add("modelmirage:p", asset("p.json", 4 * MIB));
		ok.add("parents", parents);
		assertEquals(1, parse(ok).get(1).parents().size());
		JsonObject bad = entry("b");
		JsonObject oversized = new JsonObject();
		oversized.add("modelmirage:p", asset("p.json", 4 * MIB + 1));
		bad.add("parents", oversized);
		assertThrows(IllegalArgumentException.class, () -> parse(bad));
	}

	@Test
	void textureCountLimitDependsOnKind() {
		JsonObject item = entry("item");
		item.add("textures", assets(32, 1));
		assertEquals(32, parse(item).get(1).textures().size());
		JsonObject tooManyForItem = entry("item2");
		tooManyForItem.add("textures", assets(33, 1));
		assertThrows(IllegalArgumentException.class, () -> parse(tooManyForItem));

		JsonObject mascot = entry("mascot");
		mascot.addProperty("kind", "mascot");
		mascot.add("textures", assets(256, 1));
		assertEquals(256, parse(mascot).get(1).textures().size());
		JsonObject tooManyForMascot = entry("mascot2");
		tooManyForMascot.addProperty("kind", "mascot");
		tooManyForMascot.add("textures", assets(257, 1));
		assertThrows(IllegalArgumentException.class, () -> parse(tooManyForMascot));
	}

	@Test
	void parentCountLimitIsThirtyTwo() {
		JsonObject ok = entry("a");
		ok.add("parents", assets(32, 1));
		assertEquals(32, parse(ok).get(1).parents().size());
		JsonObject bad = entry("b");
		bad.add("parents", assets(33, 1));
		assertThrows(IllegalArgumentException.class, () -> parse(bad));
	}

	@Test
	void totalSizeLimitIsThirtyTwoMiB() {
		JsonObject ok = entry("a");
		ok.add("model", asset("m.json", 16 * MIB));
		ok.add("textures", assets(1, 16 * MIB));
		assertEquals(2, parse(ok).size());

		JsonObject oneOver = entry("b");
		oneOver.add("model", asset("m.json", 16 * MIB));
		JsonObject textures = assets(1, 16 * MIB);
		textures.add("modelmirage:extra", asset("textures/extra.png", 1));
		oneOver.add("textures", textures);
		assertThrows(IllegalArgumentException.class, () -> parse(oneOver));

		JsonObject mixed = entry("c");
		mixed.add("model", asset("m.json", 16 * MIB));
		mixed.add("textures", assets(1, 8 * MIB));
		mixed.add("parents", assets(3, 4 * MIB));
		assertThrows(IllegalArgumentException.class, () -> parse(mixed));
	}

	@Test
	void totalSizeIsPerModelNotPerCatalog() {
		JsonObject first = entry("a");
		first.add("model", asset("m.json", 16 * MIB));
		first.add("textures", assets(1, 16 * MIB));
		JsonObject second = entry("b");
		second.add("model", asset("m2.json", 16 * MIB));
		second.add("textures", assets(1, 16 * MIB));
		assertEquals(3, parse(first, second).size());
	}

	@Test
	void rejectsInvalidDependencyIdentifier() {
		JsonObject entry = entry("a");
		JsonObject textures = new JsonObject();
		textures.add("NOT VALID", asset("t.png", 1));
		entry.add("textures", textures);
		assertThrows(RuntimeException.class, () -> parse(entry));
	}

	@Test
	void oneInvalidEntryRejectsWholeCatalog() {
		JsonObject bad = entry("bad");
		bad.addProperty("name", "");
		assertThrows(IllegalArgumentException.class, () -> parse(entry("good"), bad));
	}

	@Test
	void mascotIsNotReplacedByCatalogEntries() {
		CatalogModel first = parse(entry("a")).get(0);
		assertSame(CatalogModel.Kind.MASCOT, first.kind());
		assertTrue(first.builtin());
	}
}
