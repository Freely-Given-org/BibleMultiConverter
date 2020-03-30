package biblemulticonverter.format;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import biblemulticonverter.data.Bible;
import biblemulticonverter.data.Book;
import biblemulticonverter.data.BookID;
import biblemulticonverter.data.Chapter;
import biblemulticonverter.data.FormattedText;
import biblemulticonverter.data.FormattedText.ExtraAttributePriority;
import biblemulticonverter.data.FormattedText.FormattingInstructionKind;
import biblemulticonverter.data.FormattedText.LineBreakKind;
import biblemulticonverter.data.FormattedText.RawHTMLMode;
import biblemulticonverter.data.FormattedText.Visitor;
import biblemulticonverter.data.Verse;

public class Diffable implements RoundtripFormat {

	public static final String[] HELP_TEXT = {
			"A VPL-like text-format that can be diffed easily.",
			"",
			"Every verse will be put into its own line, similar to VPL.",
			"Linebreaks or headlines get their own lines.",
			"Formatting is stored in HTML-like tags.",
			"This format is ideal for fixing/editing modules."
	};

	private static final String MAGIC = "BibleMultiConverter-1.0 Title: ";

	@Override
	public void doExport(Bible bible, String... exportArgs) throws Exception {
		File exportFile = new File(exportArgs[0]);
		try (Writer w = new OutputStreamWriter(new FileOutputStream(exportFile), StandardCharsets.UTF_8)) {
			doExport(bible, w);
		}
	}

	protected void doExport(Bible bible, Writer w) throws IOException {
		w.write(MAGIC + bible.getName() + "\n");
		for (Book book : bible.getBooks()) {
			w.write(book.getAbbr() + " = " + book.getId().getOsisID() + "\t" + book.getShortName() + "\t" + book.getLongName() + "\n");
			int chapterNumber = 0;
			for (Chapter ch : book.getChapters()) {
				chapterNumber++;
				if (ch.getProlog() != null) {
					ch.getProlog().accept(new DiffableVisitor(w, book.getAbbr() + " " + chapterNumber + " "));
				}
				for (Verse v : ch.getVerses()) {
					v.accept(new DiffableVisitor(w, book.getAbbr() + " " + chapterNumber + ":" + v.getNumber() + " "));
				}
			}
		}
	}

	@Override
	public Bible doImport(File inputFile) throws Exception {
		try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(inputFile), StandardCharsets.UTF_8))) {
			return doImport(br);
		}
	}

	protected Bible doImport(BufferedReader br) throws IOException {
		String line = br.readLine();
		if (!line.startsWith(MAGIC))
			throw new IOException("Invalid header line: " + line);
		Bible result = new Bible(line.substring(MAGIC.length()));
		Map<String, Book> bookMap = new HashMap<String, Book>();
		while ((line = br.readLine()) != null) {
			line = line.trim();
			if (line.length() == 0 || line.startsWith("#"))
				continue;

			String[] parts = line.split(" ", 3);
			if (parts.length != 3)
				throw new IOException("Not enough fields: " + line);
			try {
				if (parts[1].equals("=")) {
					String[] fields = parts[2].split("\t");
					if (fields.length != 3)
						throw new IOException("Malformed header line (not 3 fields): " + parts[3]);
					BookID id = BookID.fromOsisId(fields[0]);
					if (id == null)
						throw new IOException("Unknown book ID: " + fields[0]);
					Book newBook = new Book(parts[0], id, fields[1], fields[2]);
					result.getBooks().add(newBook);
					Book oldBook = bookMap.get(parts[0]);
					if (oldBook != null) {
						newBook.getChapters().addAll(oldBook.getChapters());
						result.getBooks().remove(oldBook);
					}
					bookMap.put(parts[0], newBook);
					continue;
				}
				Book book = bookMap.get(parts[0]);
				if (book == null)
					throw new IOException("Unknown book prefix (header line missing?): " + parts[0]);
				if (parts[1].equals("->")) {
					if (!parts[2].equals("-")) {
						new StrippedDiffable().renameBookInXref(result, parts[0], parts[2], false);
						Book destBook = bookMap.get(parts[2]);
						if (destBook == null)
							throw new IOException("Unknown destination book (header line missing?): " + parts[2]);
						destBook.getChapters().addAll(book.getChapters());
					}
					result.getBooks().remove(book);
					bookMap.remove(parts[0]);
					continue;
				} else if (parts[1].equals("^^")) {
					Book destBook = bookMap.get(parts[2]);
					if (destBook == null)
						throw new IOException("Unknown destination book (header line missing?): " + parts[2]);
					result.getBooks().remove(book);
					result.getBooks().add(result.getBooks().indexOf(destBook), book);
					continue;
				}
				int chapterNumber;
				String verse;
				if (parts[1].contains(":")) {
					String[] chapVerse = parts[1].split(":", 2);
					chapterNumber = Integer.parseInt(chapVerse[0]);
					verse = chapVerse[1];
				} else {
					chapterNumber = Integer.parseInt(parts[1]);
					verse = null;
				}
				while (book.getChapters().size() < chapterNumber) {
					book.getChapters().add(new Chapter());
				}
				Chapter chapter = book.getChapters().get(chapterNumber - 1);
				FormattedText target;
				if (verse == null) {
					if (chapter.getProlog() == null)
						chapter.setProlog(new FormattedText());
					target = chapter.getProlog();
				} else {
					int idx = chapter.getVerseIndex(verse);
					if (idx == -1) {
						Verse v = new Verse(verse);
						chapter.getVerses().add(v);
						target = v;
					} else {
						target = chapter.getVerses().get(idx);
					}
				}
				String contents = parts[2];
				int lastPos = 0, pos = contents.indexOf('<');
				List<Visitor<RuntimeException>> visitorStack = new ArrayList<Visitor<RuntimeException>>();
				visitorStack.add(target.getAppendVisitor());
				while (pos != -1) {
					if (pos > lastPos) {
						visitorStack.get(visitorStack.size() - 1).visitText(contents.substring(lastPos, pos));
					}
					lastPos = parseSingleTag(contents, pos, visitorStack);
					pos = contents.indexOf('<', lastPos);
				}
				if (lastPos < contents.length())
					visitorStack.get(visitorStack.size() - 1).visitText(contents.substring(lastPos));
				if (visitorStack.size() > 1)
					throw new RuntimeException("Unclosed tags: " + contents);
			} catch (Exception ex) {
				throw new IOException("Error while parsing line: " + line, ex);
			}

		}
		for (Book book : result.getBooks()) {
			for (Chapter chapter : book.getChapters()) {
				if (chapter.getProlog() != null)
					chapter.getProlog().finished();
				for (Verse v : chapter.getVerses())
					v.finished();
			}
		}
		return result;
	}

	@Override
	public boolean isExportImportRoundtrip() {
		return true;
	}

	@Override
	public boolean isImportExportRoundtrip() {
		return true;
	}

	protected static int parseSingleTag(String line, int pos, List<Visitor<RuntimeException>> visitorStack) throws IOException {
		int lastPos;
		Visitor<RuntimeException> visitor = visitorStack.remove(visitorStack.size() - 1);
		int endPos = line.indexOf('>', pos);
		if (endPos == -1)
			throw new IOException("Unclosed tag: " + line.substring(pos));

		String tag = line.substring(pos + 1, endPos);
		if (tag.length() > 1 && tag.endsWith("/"))
			tag = tag.substring(0, tag.length() - 1);
		Map<String, String> tagArgs = new HashMap<String, String>();
		lastPos = endPos + 1;
		if (tag.contains(" ")) {
			int tpos = tag.indexOf(' ');
			while (tpos < tag.length()) {
				if (tag.charAt(tpos) == ' ')
					tpos++;
				int aspos = tag.indexOf("=\"", tpos);
				int aepos = tag.indexOf("\"", aspos + 2);
				if (aspos == -1 || aepos == -1)
					throw new IOException("Malformed tag: <" + tag + ">");
				tagArgs.put(tag.substring(tpos, aspos), tag.substring(aspos + 2, aepos));
				tpos = aepos + 1;
			}
			tag = tag.substring(0, tag.indexOf(' '));
		}
		if (tag.startsWith("/")) {
			visitor = visitorStack.remove(visitorStack.size() - 1);
		} else if (tag.length() == 1 && tag.charAt(0) >= 'a' && tag.charAt(0) <= 'z') {
			visitorStack.add(visitor);
			visitor = visitor.visitFormattingInstruction(FormattingInstructionKind.fromChar(tag.charAt(0)));
		} else if (tag.length() == 2 && tag.startsWith("h") && tag.charAt(1) >= '1' && tag.charAt(1) <= '9') {
			visitorStack.add(visitor);
			visitor = visitor.visitHeadline(tag.charAt(1) - '0');
		} else if (tag.startsWith("raw:")) {
			validateTagArgs(tag, tagArgs, "mode");
			int markerPos = line.indexOf("</" + tag + ">", lastPos);
			visitor.visitRawHTML(RawHTMLMode.valueOf(tagArgs.get("mode")), line.substring(lastPos, markerPos));
			lastPos = markerPos + tag.length() + 3;
		} else {
			switch (tag) {
			case "<":
				visitor.visitText("<");
				break;
			case "fn":
				visitorStack.add(visitor);
				visitor = visitor.visitFootnote();
				break;
			case "css":
				validateTagArgs(tag, tagArgs, "style");
				visitorStack.add(visitor);
				visitor = visitor.visitCSSFormatting(tagArgs.get("style"));
				break;
			case "vs":
				visitor.visitVerseSeparator();
				break;
			case "br":
				validateTagArgs(tag, tagArgs, "kind");
				visitor.visitLineBreak(LineBreakKind.valueOf(tagArgs.get("kind")));
				break;
			case "grammar":
				validateTagArgs(tag, tagArgs, "strong", "rmac", "idx");
				visitorStack.add(visitor);
				visitor = visitor.visitGrammarInformation(tagArgs.containsKey("strongpfx") ? tagArgs.get("strongpfx").toCharArray() : null, intArray(tagArgs.get("strong")), tagArgs.get("rmac").length() == 0 ? null : tagArgs.get("rmac").split(","), intArray(tagArgs.get("idx")));
				break;
			case "dict":
				validateTagArgs(tag, tagArgs, "dictionary", "entry");
				visitorStack.add(visitor);
				visitor = visitor.visitDictionaryEntry(tagArgs.get("dictionary"), tagArgs.get("entry"));
				break;
			case "var":
				validateTagArgs(tag, tagArgs, "vars");
				visitorStack.add(visitor);
				visitor = visitor.visitVariationText(tagArgs.get("vars").split(","));
				break;
			case "extra":
				validateTagArgs(tag, tagArgs, "prio", "category", "key", "value");
				visitorStack.add(visitor);
				visitor = visitor.visitExtraAttribute(ExtraAttributePriority.valueOf(tagArgs.get("prio")), tagArgs.get("category"), tagArgs.get("key"), tagArgs.get("value"));
				break;
			case "xref":
				validateTagArgs(tag, tagArgs, "abbr", "id", "chapters", "verses");
				String[] chapters = tagArgs.get("chapters").split(":");
				String[] verses = tagArgs.get("verses").split(":");
				if (chapters.length != 2 || verses.length != 2)
					throw new IOException("Malformed \"abbr\" tag arguments: " + tagArgs);
				visitorStack.add(visitor);
				visitor = visitor.visitCrossReference(tagArgs.get("abbr"), BookID.fromOsisId(tagArgs.get("id")), Integer.parseInt(chapters[0]), verses[0], Integer.parseInt(chapters[1]), verses[1]);
				break;
			default:
				throw new IOException("Unsupported tag: " + tag);
			}
		}
		visitorStack.add(visitor);
		return lastPos;
	}

	private static int[] intArray(String string) {
		if (string.length() == 0)
			return null;
		String[] parts = string.split(",");
		int[] result = new int[parts.length];
		for (int i = 0; i < result.length; i++) {
			result[i] = Integer.parseInt(parts[i]);
		}
		return result;
	}

	private static void validateTagArgs(String tag, Map<String, String> tagArgs, String... requiredArgs) throws IOException {
		for (String arg : requiredArgs) {
			if (!tagArgs.containsKey(arg))
				throw new IOException("Missing argument " + arg + " in " + tag + " tag with args: " + tagArgs);
		}
	}

	protected static class DiffableVisitor implements Visitor<IOException> {
		private final Writer w;
		private final String linePrefix;
		private final DiffableVisitor childVisitor;
		private boolean startNewLine = false, inMainContent = false;

		private DiffableVisitor(Writer w, String linePrefix) throws IOException {
			this.w = w;
			this.linePrefix = linePrefix;
			childVisitor = linePrefix == null ? this : new DiffableVisitor(w, null);
			if (linePrefix != null)
				w.write(linePrefix);
		}

		protected DiffableVisitor(StringWriter sw) throws IOException {
			this(sw, "");
		}

		@Override
		public int visitElementTypes(String elementTypes) throws IOException {
			return 0;
		}

		@Override
		public Visitor<IOException> visitHeadline(int depth) throws IOException {
			if (inMainContent)
				startNewLine = true;
			checkLine();
			if (linePrefix != null)
				startNewLine = true;
			w.write("<h" + depth + ">");
			return childVisitor;
		}

		@Override
		public void visitStart() throws IOException {
			if (linePrefix != null)
				inMainContent = true;
		}

		@Override
		public void visitText(String text) throws IOException {
			checkLine();
			w.write(text.replace("<", "<<>"));
		}

		@Override
		public Visitor<IOException> visitFootnote() throws IOException {
			checkLine();
			w.write("<fn>");
			return childVisitor;
		}

		@Override
		public Visitor<IOException> visitCrossReference(String bookAbbr, BookID book, int firstChapter, String firstVerse, int lastChapter, String lastVerse) throws IOException {
			checkLine();
			w.write("<xref abbr=\"" + bookAbbr + "\" id=\"" + book.getOsisID() + "\" chapters=\"" + firstChapter + ":" + lastChapter + "\" verses=\"" + firstVerse + ":" + lastVerse + "\">");
			return childVisitor;
		}

		@Override
		public Visitor<IOException> visitFormattingInstruction(FormattingInstructionKind kind) throws IOException {
			checkLine();
			w.write("<" + kind.getCode() + ">");
			return childVisitor;
		}

		@Override
		public Visitor<IOException> visitCSSFormatting(String css) throws IOException {
			checkLine();
			w.write("<css style=\"" + css + "\">");
			return childVisitor;
		}

		@Override
		public void visitVerseSeparator() throws IOException {
			checkLine();
			w.write("<vs/>");
		}

		@Override
		public void visitLineBreak(LineBreakKind kind) throws IOException {
			checkLine();
			w.write("<br kind=\"" + kind.name() + "\"/>");
			if (linePrefix != null)
				startNewLine = true;
		}

		@Override
		public Visitor<IOException> visitGrammarInformation(char[] strongsPrefixes, int[] strongs, String[] rmac, int[] sourceIndices) throws IOException {
			checkLine();
			w.write("<grammar strong=\"");
			if (strongs != null) {
				for (int i = 0; i < strongs.length; i++) {
					if (i > 0)
						w.write(',');
					w.write("" + strongs[i]);
				}
			}
			if (strongsPrefixes != null) {
				w.write("\" strongpfx=\"");
				w.write(strongsPrefixes);
			}
			w.write("\" rmac=\"");
			if (rmac != null) {
				for (int i = 0; i < rmac.length; i++) {
					if (i > 0)
						w.write(',');
					w.write("" + rmac[i]);
				}
			}
			w.write("\" idx=\"");
			if (sourceIndices != null) {
				for (int i = 0; i < sourceIndices.length; i++) {
					if (i > 0)
						w.write(',');
					w.write("" + sourceIndices[i]);
				}
			}
			w.write("\">");
			return childVisitor;
		}

		@Override
		public Visitor<IOException> visitDictionaryEntry(String dictionary, String entry) throws IOException {
			checkLine();
			w.write("<dict dictionary=\"" + dictionary + "\" entry=\"" + entry + "\">");
			return childVisitor;
		}

		@Override
		public void visitRawHTML(RawHTMLMode mode, String raw) throws IOException {
			checkLine();
			int marker = 1;
			while (raw.contains("</raw:" + marker + ">")) {
				marker = (int) (Math.random() * 1000000);
			}
			w.write("<raw:" + marker + " mode=\"" + mode.name() + "\">" + raw + "</raw:" + marker + ">");
		}

		@Override
		public Visitor<IOException> visitVariationText(String[] variations) throws IOException {
			w.write("<var vars=\"");
			for (int i = 0; i < variations.length; i++) {
				if (i > 0)
					w.write(",");
				w.write(variations[i]);
			}
			w.write("\">");
			return childVisitor;
		}

		@Override
		public Visitor<IOException> visitExtraAttribute(ExtraAttributePriority prio, String category, String key, String value) throws IOException {
			checkLine();
			w.write("<extra prio=\"" + prio.name() + "\" category=\"" + category + "\" key=\"" + key + "\" value=\"" + value + "\">");
			return childVisitor;
		}

		@Override
		public boolean visitEnd() throws IOException {
			if (linePrefix == null)
				w.write("</>");
			else
				w.write("\n");
			return false;
		}

		private void checkLine() throws IOException {
			if (startNewLine) {
				startNewLine = false;
				w.write('\n');
				w.write(linePrefix);
			}
		}
	}
}
