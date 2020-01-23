package org.broadinstitute.hellbender.tools.walkers.variantutils;

import com.google.common.annotations.VisibleForTesting;
import htsjdk.variant.variantcontext.*;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.*;
import org.apache.commons.lang3.tuple.Pair;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.argparser.Hidden;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.engine.FeatureContext;
import org.broadinstitute.hellbender.engine.ReadsContext;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.engine.VariantWalker;
import org.broadinstitute.hellbender.utils.IndexRange;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;
import org.broadinstitute.hellbender.utils.variant.GATKVCFHeaderLines;
import org.broadinstitute.hellbender.utils.variant.GATKVariantContextUtils;
import org.broadinstitute.hellbender.utils.variant.VcfUtils;
import picard.cmdline.programgroups.VariantManipulationProgramGroup;
import org.broadinstitute.hellbender.tools.walkers.genotyper.GenotypeAssignmentMethod;
import org.broadinstitute.hellbender.utils.read.AlignmentUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Left-align indels in a variant callset
 *
 * <p>
 * This tool takes a VCF file, left-aligns the indels and trims common bases from indels,
 * leaving them with a minimum representation. The same indel can often be placed at multiple positions and still
 * represent the same haplotype. While the standard convention with VCF is to place an indel at the left-most position
 * this isn't always done, so this tool can be used to left-align them. This tool optionally splits multiallelic
 * sites into biallelics and left-aligns individual alleles. Optionally, the tool will not trim common bases from indels.
 * </p>
 *
 * <h3>Input</h3>
 * <p>
 * A variant call set to left-align and trim.
 * </p>
 *
 * <h3>Output</h3>
 * <p>
 * A left-aligned VCF.
 * </p>
 *
 * <h3>Usage examples</h3>
 *
 * <h4>Left align and trim alleles</h4>
 * <pre>
 * gatk LeftAlignAndTrimVariants \
 *   -R reference.fasta \
 *   -V input.vcf \
 *   -O output.vcf
 * </pre>
 *
 * <h4>Left align and don't trim alleles</h4>
 * <pre>
 * gatk LeftAlignAndTrimVariants \
 *   -R reference.fasta \
 *   -V input.vcf \
 *   -O output.vcf \
 *   --dont-trim-alleles
 * </pre>
 *
 * <h4>Left align and trim alleles, process alleles <= 208 bases</h4>
 * <pre>
 * gatk LeftAlignAndTrimVariants \
 *   -R reference.fasta \
 *   -V input.vcf \
 *   -O output.vcf \
 *   --max-indel-length 208
 * </pre>
 * <h4>Split multiallics into biallelics, left align and trim alleles</h4>
 * <pre>
 * gatk LeftAlignAndTrimVariants \
 *   -R reference.fasta \
 *   -V input.vcf \
 *   -O output.vcf \
 *   --split-multi-allelics
 * </pre>
 *
 * <h4>Split multiallelics into biallics, left align but don't trim alleles, and store the original AC, AF, and AN values</h4>
 * <pre>
 * gatk LeftAlignAndTrimVariants \
 *   -R reference.fasta \
 *   -V input.vcf \
 *   -O output.vcf \
 *   --split-multi-allelics \
 *   --dont-trim-alleles
 *   --keep-original-ac
 * </pre>
 * <h4> Left align variants up to 2000 bases to the left (default is at most left aligning 1000 bases to left)</h4>
 * <pre>
 * gatk LeftAlignAndTrimVariants \
 *   -R reference.fasta \
 *   -V input.vcf \
 *   -O output.vcf \
 *   --max-leading-bases 2000
 * </pre>
 */
@CommandLineProgramProperties(
        summary = "This tool takes a VCF file, left-aligns the indels and trims common bases from indels," +
                "leaving them with a minimum representation. The same indel can often be placed at multiple positions and still" +
                "represent the same haplotype. While the standard convention with VCF is to place an indel at the left-most position" +
                "this isn't always done, so this tool can be used to left-align them. This tool optionally splits multiallelic" +
                "sites into biallelics and left-aligns individual alleles. Optionally, the tool will not trim common bases from indels.",
        oneLineSummary = "Left align and trim vairants",
        programGroup = VariantManipulationProgramGroup.class
)
@DocumentedFeature
public class LeftAlignAndTrimVariants extends VariantWalker {

    public static final String DONT_TRIM_ALLELES_LONG_NAME = "dont-trim-alleles";
    public static final String DONT_TRIM_ALLELES_SHORT_NAME = "no-trim";
    public static final String SPLIT_MULTIALLELEICS_LONG_NAME = "split-multi-allelics";
    public static final String KEEP_ORIGINAL_AC_LONG_NAME = "keep-original-ac";
    public static final String MAX_INDEL_LENGTH_LONG_NAME = "max-indel-length";

    public static final int DEFAULT_MAX_LEADING_BASES= 1000;

    @VisibleForTesting
    static final int DEFAULT_MAX_INDEL_SIZE = 200;

    public static final String MAX_LEADING_BASES_LONG_NAME = "max-leading-bases";
    /**
     * Output file to which to write left aligned variants
     */
    @Argument(fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME,
            shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            doc = "File to which variants should be written")
    public File outFile = null;
    /**
     * If this argument is set, bases common to all alleles will not be removed and will not leave their minimal representation.
     */
    @Argument(fullName = DONT_TRIM_ALLELES_LONG_NAME, shortName = DONT_TRIM_ALLELES_SHORT_NAME, doc = "Do not Trim alleles to remove bases common to all of them", optional = true)
    protected boolean dontTrimAlleles = false;

    /**
     * If this argument is set, split multiallelic records and left-align individual alleles.
     * If this argument is not set, multiallelic records are not attempted to left-align and will be copied as is.
     */
    @Argument(fullName = SPLIT_MULTIALLELEICS_LONG_NAME, doc = "Split multiallelic records and left-align individual alleles", optional = true)
    protected boolean splitMultiallelics = false;

    /**
     * When subsetting a callset, this tool recalculates the AC, AF, and AN values corresponding to the contents of the
     * subset. If this flag is enabled, the original values of those annotations will be stored in new annotations called
     * AC_Orig, AF_Orig, and AN_Orig.
     */
    @Argument(fullName = KEEP_ORIGINAL_AC_LONG_NAME, doc = "Store the original AC, AF, and AN values after subsetting", optional = true)
    private boolean keepOriginalChrCounts = false;

    /**
     * Maximum indel size to realign.  Indels larger than this will be left unadjusted.
     */
    @Argument(fullName = MAX_INDEL_LENGTH_LONG_NAME, doc = "Set maximum indel size to realign", optional = true)
    protected int maxIndelSize = DEFAULT_MAX_INDEL_SIZE;

    /**
     * Distance in reference to look back before allele
     */
    @Argument(fullName = MAX_LEADING_BASES_LONG_NAME, doc = "Set max reference window size to look back before allele", optional = true)
    protected int maxLeadingBases = DEFAULT_MAX_LEADING_BASES;

    @Hidden
    @Argument(fullName = "suppress-reference-path", optional = true,
            doc = "Suppress reference path in output for test result differencing")
    private boolean suppressReferencePath = false;

    private VariantContextWriter vcfWriter = null;

    // it is possible that left-alignment changes the order of variants.  For example, an indel in an STR may left align past a
    // SNP in the same STR that doesn't move.  Thus we can only emit variants that are sufficiently far behind the current one.
    PriorityQueue<VariantContext> pendingVariants;

    @Override
    public void onTraversalStart() {
        final Map<String, VCFHeader> vcfHeaders = Collections.singletonMap(getDrivingVariantsFeatureInput().getName(), getHeaderForVariants());
        final SortedSet<String> vcfSamples = VcfUtils.getSortedSampleSet(vcfHeaders, GATKVariantContextUtils.GenotypeMergeType.REQUIRE_UNIQUE);

        pendingVariants = new PriorityQueue<>(new VariantContextComparator(getReferenceDictionary()));

        // Initialize VCF header lines
        final Path refPath = referenceArguments.getReferencePath();
        final Set<VCFHeaderLine> actualLines = VcfUtils.updateHeaderContigLines(createVCFHeaderLineList(vcfHeaders), refPath, getReferenceDictionary(), suppressReferencePath);

        vcfWriter = createVCFWriter(outFile);
        vcfWriter.writeHeader(new VCFHeader(actualLines, vcfSamples));
    }

    /**
     * Prepare the VCF header lines
     */
    private Set<VCFHeaderLine> createVCFHeaderLineList(Map<String, VCFHeader> vcfHeaders) {
        final Set<VCFHeaderLine> headerLines = VCFUtils.smartMergeHeaders(vcfHeaders.values(), true);
        headerLines.addAll(getDefaultToolVCFHeaderLines());

        if (splitMultiallelics) {
            GATKVariantContextUtils.addChromosomeCountsToHeader(headerLines);
        }

        if (keepOriginalChrCounts) {
            headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.ORIGINAL_AC_KEY));
            headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.ORIGINAL_AF_KEY));
            headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.ORIGINAL_AN_KEY));
        }
        return headerLines;
    }


    /**
     * Left aligns variants in repetitive regions.  Also trims alleles and splits multiallelics to biallelics, if desired
     */
    @Override
    public void apply(VariantContext vc, ReadsContext readsContext, ReferenceContext ref, FeatureContext featureContext) {

        // write any pending variants that are sufficiently far to the left that there's no chance of left-aligning new variants past them
        while (!pendingVariants.isEmpty()) {
            final VariantContext leftmostPendingVariant = pendingVariants.peek();
            if (leftmostPendingVariant.getEnd() < vc.getEnd() - maxLeadingBases || !leftmostPendingVariant.contigsMatch(vc)) {
                vcfWriter.add(pendingVariants.poll());
            } else {
                break;
            }
        }

        final List<VariantContext> vcList = splitMultiallelics ? GATKVariantContextUtils.splitVariantContextToBiallelics(vc, false,
                GenotypeAssignmentMethod.BEST_MATCH_TO_ORIGINAL, keepOriginalChrCounts) : Collections.singletonList(vc);

        for (final VariantContext splitVariant : vcList) {
            final List<Integer> indelLengths = splitVariant.getIndelLengths();
            final int indelLength = indelLengths == null ? 0 : indelLengths.stream().map(Math::abs).max(Integer::compareTo).orElse(0);

            if (indelLength > maxIndelSize) {
                logger.info(String.format("%s (%d) at position %s:%d; skipping that record. Set --max-indel-length >= %d",
                        "Indel is too long", indelLength, splitVariant.getContig(), splitVariant.getStart(), indelLength));
                pendingVariants.add(splitVariant);
            } else {
                final VariantContext trimmed = dontTrimAlleles ? splitVariant : GATKVariantContextUtils.trimAlleles(splitVariant, true, true);
                pendingVariants.add(leftAlign(trimmed, ref, maxLeadingBases));
            }
        }
    }

    /**
     * Reference is required for left alignment
     */
    @Override
    public boolean requiresReference() {
        return true;
    }

    /**
     * Print out message of how many variants were realigned
     *
     * @return
     */
    @Override
    public Object onTraversalSuccess() {
        while (!pendingVariants.isEmpty()) {
            vcfWriter.add(pendingVariants.poll());
        }
        return "SUCCESS";
    }

    /**
     * Close out the new variants file.
     */
    @Override
    public void closeTool() {
        if (vcfWriter != null) {
            vcfWriter.close();
        }
    }

    /**
     * Main routine workhorse. By definition, it will only take biallelic vc's. Splitting into multiple alleles has to be
     * handled by calling routine.
     *
     * @param vc  Input VC with variants to left align
     * @param ref Reference context
     * @return new VC.
     */
    @VisibleForTesting
    static VariantContext leftAlign(final VariantContext vc, final ReferenceContext ref, final int maxLeadingBases) {
        if (!vc.isIndel()) {
            return vc;
        }

        for(int leadingBases = 10; leadingBases < maxLeadingBases; leadingBases *= 2) {
            final int refStart = Math.max(vc.getStart() - leadingBases, 1);

            // reference sequence starting before the variant (to give space for left-alignment) and ending at the variant end
            final byte[] refSeq = ref.getBases(new SimpleInterval(vc.getContig(), refStart, vc.getEnd()));

            final int variantOffsetInRef = vc.getStart() - refStart;

            final List<byte[]> sequences = vc.getAlleles().stream().map(a -> {
                final byte[] result = new byte[variantOffsetInRef + a.length()];
                System.arraycopy(refSeq, 0, result, 0, variantOffsetInRef);
                System.arraycopy(a.getBases(), 0, result, variantOffsetInRef, a.length());
                return result;
            }).collect(Collectors.toList());

            final List<IndexRange> alleleRanges = vc.getAlleles().stream()
                    .map(a -> new IndexRange(variantOffsetInRef + 1, variantOffsetInRef + a.length()))
                    .collect(Collectors.toList());

            // note that this also shifts the index ranges as a side effect, so below they can be used to output allele bases
            // since trimming is performed elsewhere, we care only about the start shift
            final int shift = AlignmentUtils.leftAlignAlleles(sequences, alleleRanges, variantOffsetInRef).getLeft();

            if (shift == 0) {
                return vc;
            } else if (shift == variantOffsetInRef) {
                continue;
            }

            final Map<Allele, Allele> alleleMap = IntStream.range(0, alleleRanges.size()).boxed()
                    .collect(Collectors.toMap(
                            n -> vc.getAlleles().get(n),
                            n -> Allele.create(Arrays.copyOfRange(sequences.get(n), variantOffsetInRef - shift, variantOffsetInRef - shift + vc.getAlleles().get(n).length()), n == 0)));

            final GenotypesContext newGenotypes = GenotypesContext.create(vc.getNSamples());
            for (final Genotype genotype : vc.getGenotypes()) {
                final List<Allele> newAlleles = genotype.getAlleles().stream().map(a -> alleleMap.getOrDefault(a, Allele.NO_CALL)).collect(Collectors.toList());
                newGenotypes.add(new GenotypeBuilder(genotype).alleles(newAlleles).make());
            }

            return new VariantContextBuilder(vc).start(vc.getStart() - shift).stop(vc.getEnd() - shift)
                    .alleles(alleleMap.values()).genotypes(newGenotypes).make();
        }

        return vc;
    }
}
