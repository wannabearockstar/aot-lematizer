package me.bazhenov.aot;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Ints;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.newLinkedList;
import static com.google.common.io.ByteStreams.copy;
import static com.google.common.io.Closeables.close;
import static com.google.common.io.Closeables.closeQuietly;
import static java.lang.Integer.parseInt;

public class DictionaryCompiler {

	@Parameter(names = "-blockSize", description = "The size of dictionary block")
	public int blockSize = 16;

	@Parameter(names = "-mrd", description = "Path to MRD file")
	public String mrd;

	@Parameter(names = "-tab", description = "Path to TAB file")
	public String tab;

	@Parameter(names = "-out", description = "Path to output file")
	public String out;

	public static void main(String[] args) throws IOException {
		DictionaryCompiler compiler = new DictionaryCompiler();
		new JCommander(compiler, args);
		InputStream mrd = new BufferedInputStream(new FileInputStream(compiler.mrd));
		InputStream tab = new BufferedInputStream(new FileInputStream(compiler.tab));
		BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(compiler.out));
		try {
			compiler.compileDictionary(mrd, tab, output);
		} finally {
			closeQuietly(mrd);
			closeQuietly(tab);
			close(output, false);
		}
	}

	public void compileDictionary(InputStream mrd, InputStream tab, OutputStream out) throws IOException {
		ZipOutputStream zip = new ZipOutputStream(out);
		BufferedInputStream is = new BufferedInputStream(mrd);
		BufferedReader reader = new BufferedReader(new InputStreamReader(is));
		List<List<Flexion>> allFlexions = readSection(reader, new FlexionMapper());
		readSection(reader, new NullMapper()); // accentual models
		readSection(reader, new NullMapper()); // user sessions
		readSection(reader, new NullMapper()); // prefix sets
		List<Lemma> lemmas = readSection(reader, new LemmaMapper());
		reader.close();
		is.close();

		AtomicInteger sequence = new AtomicInteger(1);
		List<Variation> allTheWords = newArrayList();
		for (Lemma l : lemmas) {
			List<Variation> lemmaVariations = buildAllVariations(l, allFlexions.get(l.getFlexionIndex()), sequence);
			Variation lemmaVariation = lemmaVariations.get(0);

			for (Variation v : lemmaVariations) {
				v.setLemmaIndex(lemmaVariation.getId());
				allTheWords.add(v);
			}
		}
		Collections.sort(allTheWords, new VariationComparator());

		List<Variation> words = new ArrayList<Variation>(blockSize);
		List<Block> blocks = newArrayList();
		int[] idIndex = new int[allTheWords.size() + 1];

		String previosWord = null;
		for (Variation v : allTheWords) {
			if (words.size() >= blockSize && (previosWord != null && !v.getWord().equalsIgnoreCase(previosWord))) {
				Block block = new Block(words);

				blocks.add(block);
				words = new ArrayList<Variation>(blockSize);
			}
			idIndex[v.getId()] = blocks.size();
			words.add(v);
			previosWord = v.getWord();
		}
		Block block = new Block(words);
		blocks.add(block);

		zip.putNextEntry(new ZipEntry("mrd"));

		writeInt(zip, blocks.size());
		for (Block b : blocks) {
			b.writeTo(zip);
		}

		writeInt(zip, idIndex.length);
		for (int blockOffset : idIndex) {
			writeInt(zip, blockOffset);
		}

		zip.putNextEntry(new ZipEntry("tab"));
		copy(tab, zip);

		zip.close();
	}

	public static List<Variation> buildAllVariations(Lemma lemma, List<Flexion> flexions, AtomicInteger sequence) {
		List<Variation> variations = newLinkedList();
		if (lemma.getLemma().equals("#")) {
			for (Flexion flexion : flexions) {
				variations.add(new Variation(flexion.getEnding(), flexion.getAncode(), sequence.getAndIncrement()));
			}
		} else {
			for (Flexion flexion : flexions) {
				variations.add(new Variation(lemma.getLemma() + flexion.getEnding(), flexion.getAncode(), sequence.getAndIncrement()));
			}
		}
		return variations;
	}

	public static void writeInt(OutputStream out, int length) throws IOException {
		out.write(Ints.toByteArray(length));
	}

	private static <O> List<O> readSection(BufferedReader reader, Mapper<String, O> mapper) throws IOException {
		int sectionLength = parseInt(reader.readLine());

		List<O> output = new ArrayList<O>(sectionLength);
		for (int i = 0; i < sectionLength; i++) {
			output.add(mapper.map(reader.readLine()));
		}
		return output;
	}
}