package org.broadinstitute.hellbender.tools.spark.sv.evidence;

import org.broadinstitute.hellbender.tools.spark.sv.utils.PairedStrandedIntervalTree;
import org.broadinstitute.hellbender.tools.spark.sv.utils.PairedStrandedIntervals;
import org.broadinstitute.hellbender.tools.spark.sv.utils.SVInterval;
import org.broadinstitute.hellbender.tools.spark.sv.utils.SVIntervalTree;
import org.broadinstitute.hellbender.utils.Utils;
import scala.Tuple2;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

public class EvidenceTargetLinkClusterer {

    private final ReadMetadata readMetadata;

    public EvidenceTargetLinkClusterer(final ReadMetadata readMetadata) {
        this.readMetadata = readMetadata;
    }

    public Iterator<EvidenceTargetLink> cluster(final Iterator<BreakpointEvidence> breakpointEvidenceIterator) throws Exception {
        final List<EvidenceTargetLink> links = new ArrayList<>();
        final SVIntervalTree<EvidenceTargetLink> currentIntervalsWithTargets = new SVIntervalTree<>();
        while (breakpointEvidenceIterator.hasNext()) {
            final BreakpointEvidence nextEvidence = breakpointEvidenceIterator.next();
            if (nextEvidence.hasDistalTargets(readMetadata)) {
                final SVInterval target = nextEvidence.getDistalTargets(readMetadata).get(0);
                //System.err.println(toBedpeString(nextEvidence, nextEvidence.getLocation(), target, ((BreakpointEvidence.ReadEvidence) nextEvidence).getTemplateName() +
                //        ((BreakpointEvidence.ReadEvidence) nextEvidence).getFragmentOrdinal() + (nextEvidence instanceof BreakpointEvidence.DiscordantReadPairEvidence ? "DRP" : "SR"), 1));
                EvidenceTargetLink updatedLink = null;
                for (final Iterator<SVIntervalTree.Entry<EvidenceTargetLink>> it = currentIntervalsWithTargets.overlappers(nextEvidence.getLocation()); it.hasNext(); ) {
                    final SVIntervalTree.Entry<EvidenceTargetLink> sourceIntervalEntry = it.next();
                    final EvidenceTargetLink oldLink = sourceIntervalEntry.getValue();
                    // todo: what to do if there are more than one distal targets
                    if (nextEvidence.hasDistalTargets(readMetadata) &&
                            strandsMatch(nextEvidence.isForwardStrand(), sourceIntervalEntry.getValue().sourceForwardStrand)
                        && (nextEvidence.getDistalTargets(readMetadata).get(0).overlaps(oldLink.target) &&
                                strandsMatch(nextEvidence.getDistalTargetStrands(readMetadata).get(0), oldLink.targetForwardStrand))) {
                            // if it does, intersect the source and target intervals to refine the link
                            it.remove();
                            final SVInterval newSource = sourceIntervalEntry.getInterval().intersect(nextEvidence.getLocation());
                            final SVInterval newTarget = oldLink.target.intersect(nextEvidence.getDistalTargets(readMetadata).get(0));
                            updatedLink = new EvidenceTargetLink(newSource,
                                    oldLink.sourceForwardStrand,
                                    newTarget,
                                    oldLink.targetForwardStrand,
                                    nextEvidence instanceof BreakpointEvidence.DiscordantReadPairEvidence
                                            ? oldLink.splitReads : oldLink.splitReads + 1,
                                    nextEvidence instanceof BreakpointEvidence.DiscordantReadPairEvidence
                                            ? oldLink.readPairs + 1 : oldLink.readPairs);
                            //System.err.println("updating to: " + toBedpeString(nextEvidence, newSource, newTarget, ((BreakpointEvidence.ReadEvidence) nextEvidence).getTemplateName() +
//                                    ((BreakpointEvidence.ReadEvidence) nextEvidence).getFragmentOrdinal() + "_" + updatedLink.splitReads + "_" + updatedLink.readPairs, 1));
                            break;
                    }
                }
                if (updatedLink == null) {
                    updatedLink = new EvidenceTargetLink(
                            nextEvidence.getLocation(),
                            nextEvidence.isForwardStrand(),
                            nextEvidence.getDistalTargets(readMetadata).get(0),
                            nextEvidence.getDistalTargetStrands(readMetadata).get(0),
                            nextEvidence instanceof BreakpointEvidence.DiscordantReadPairEvidence
                                    ? 0 : 1,
                            nextEvidence instanceof BreakpointEvidence.DiscordantReadPairEvidence
                                    ? 1 : 0);
                    //System.err.println("creating new: " + toBedpeString(nextEvidence, nextEvidence.getLocation(), nextEvidence.getDistalTargets(readMetadata).get(0), ((BreakpointEvidence.ReadEvidence) nextEvidence).getTemplateName() +
                    //        ((BreakpointEvidence.ReadEvidence) nextEvidence).getFragmentOrdinal() + "_" + updatedLink.splitReads + "_" + updatedLink.readPairs, 1));
                }
                currentIntervalsWithTargets.put(updatedLink.source, updatedLink);
            }
        }

        for (Iterator<SVIntervalTree.Entry<EvidenceTargetLink>> it = currentIntervalsWithTargets.iterator(); it.hasNext(); ) {
            links.add(it.next().getValue());
        }

        return links.iterator();
    }

    public String toBedpeString(final BreakpointEvidence nextEvidence, final SVInterval source, final SVInterval target, final String id, final int score) {
        return "21" + "\t" + (source.getStart() - 1) + "\t" + source.getEnd() +
                "\t" + "21" + "\t" + (target.getStart() - 1) + "\t" + target.getEnd() + "\t"  +
                id + "\t" +
                score + "\t" + (nextEvidence.isForwardStrand() ? "+" : "-") + "\t" + (nextEvidence.getDistalTargetStrands(readMetadata).get(0) ? "+" : "-");
    }

    private static boolean strandsMatch(final Boolean forwardStrand1, final Boolean forwardStrand2) {
        return forwardStrand1 != null && forwardStrand2 != null && forwardStrand1.equals(forwardStrand2);
    }

    /**
     * Combines links that agree on interval-pair and orientation but have source and target switched. For
     * example, if link1 has intervals s1 and t1, and link2 has s2 and t2, and s1 overlaps t1 and s2 overlaps t2,
     * the links will be combined as long as strands agree. Returned links have the intersection of two paired intervals as
     * source and target, with the lower-coordinate interval appearing as source.
     */
    static List<EvidenceTargetLink> deduplicateTargetLinks(final List<EvidenceTargetLink> evidenceTargetLinks) {

        PairedStrandedIntervalTree<EvidenceTargetLink> pairedStrandedIntervalTree = new PairedStrandedIntervalTree<>();

        evidenceTargetLinks.stream().filter(link -> link.source.compareTo(link.target) < 0).forEach(link -> pairedStrandedIntervalTree.put(link.getPairedStrandedIntervals(), link));


        evidenceTargetLinks.stream().filter(link -> link.source.compareTo(link.target) >= 0).forEach(link -> {

            PairedStrandedIntervals reversedPair =
                    new PairedStrandedIntervals(link.target, link.targetForwardStrand, link.source, link.sourceForwardStrand);

            Iterator<Tuple2<PairedStrandedIntervals, EvidenceTargetLink>> psiOverlappers =
                    pairedStrandedIntervalTree.overlappers(reversedPair);

            EvidenceTargetLink newLink = null;
            while (psiOverlappers.hasNext()) {
                EvidenceTargetLink existingLink = psiOverlappers.next()._2();
                newLink = new EvidenceTargetLink(link.target.intersect(existingLink.source), link.targetForwardStrand,
                        link.source.intersect(existingLink.target), link.sourceForwardStrand,
                        Math.max(link.splitReads, existingLink.splitReads), Math.max(link.readPairs, existingLink.readPairs));
                psiOverlappers.remove();
                break;
            }

            if (newLink != null) {
                pairedStrandedIntervalTree.put(newLink.getPairedStrandedIntervals(), newLink);
            } else {
                pairedStrandedIntervalTree.put(reversedPair, new EvidenceTargetLink(reversedPair.getLeft(), reversedPair.getLeftStrand(), reversedPair.getRight(), reversedPair.getRightStrand(), link.splitReads, link.readPairs));
            }

        });

        return Utils.stream(pairedStrandedIntervalTree.iterator()).map(Tuple2::_2).collect(Collectors.toList());
    }

}
