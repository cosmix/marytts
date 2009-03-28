/**
 * Copyright 2009 DFKI GmbH.
 * All Rights Reserved.  Use is subject to license terms.
 *
 * This file is part of MARY TTS.
 *
 * MARY TTS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package marytts.tools.voiceimport.traintrees;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import marytts.cart.CART;
import marytts.cart.DecisionNode;
import marytts.cart.DirectedGraph;
import marytts.cart.DirectedGraphNode;
import marytts.cart.FeatureVectorCART;
import marytts.cart.LeafNode;
import marytts.cart.Node;
import marytts.cart.LeafNode.FeatureVectorLeafNode;
import marytts.cart.impose.FeatureArrayIndexer;
import marytts.cart.impose.MaryNode;
import marytts.features.FeatureDefinition;
import marytts.features.FeatureVector;
import marytts.unitselection.data.FeatureFileReader;

/**
 * @author marc
 *
 */
public class AgglomerativeClusterer
{
    private static final float SINGLE_ITEM_IMPURITY = 0;
    private FeatureVector[] trainingFeatures;
    private FeatureVector[] testFeatures;
    private Map<LeafNode, Double> impurities = new HashMap<LeafNode, Double>();
    private DistanceMeasure dist;
    private FeatureDefinition featureDefinition;
    private int numByteFeatures;
    private int[] availableFeatures;
    
    public AgglomerativeClusterer(FeatureVector[] features, FeatureDefinition featureDefinition, List<String> featuresToUse, DistanceMeasure dist)
    {
        float proportionTestData = 0.1f;
        int nSkip = (int)(1/proportionTestData); // we use every nSkip'th feature vector as test data
        this.testFeatures = new FeatureVector[features.length / nSkip];
        this.trainingFeatures = new FeatureVector[features.length - testFeatures.length];
        int iTest = 0, iTrain = 0;
        for (int i=0; i<features.length; i++) {
            if (i%nSkip == 0) {
                testFeatures[iTest++] = features[i];
            } else {
                trainingFeatures[iTrain++] = features[i];
            }
        }
        
        this.dist = dist;
        this.featureDefinition = featureDefinition;
        this.numByteFeatures = featureDefinition.getNumberOfByteFeatures();
        if (featuresToUse != null) {
            availableFeatures = new int[featuresToUse.size()];
            for (int i=0; i<availableFeatures.length; i++) {
                availableFeatures[i] = featureDefinition.getFeatureIndex(featuresToUse.get(i));
            }
        } else { // no features given, use all byte-valued features
            availableFeatures = new int[numByteFeatures];
            for (int i=0; i<numByteFeatures; i++) {
                availableFeatures[i] = i;
            }
        }
        
    }
    
    public DirectedGraph cluster()
    {
        DirectedGraph graph = new DirectedGraph(featureDefinition);
        graph.setRootNode(new DirectedGraphNode(null, null));
        return cluster(graph, new int[0]);
    }
    
    private DirectedGraph cluster(DirectedGraph graphSoFar, int[] prevFeatureList)
    {
        long startTime = System.currentTimeMillis();
        int[] newFeatureList = new int[prevFeatureList.length+1];
        System.arraycopy(prevFeatureList, 0, newFeatureList, 0, prevFeatureList.length);
        
        // Step 1: Feature selection
        // We look for the feature that yields the best (=lowest) global impurity.
        // Stop criterion: when the best feature does not substantially add new leaves.
        FeatureArrayIndexer fai = new FeatureArrayIndexer(trainingFeatures, featureDefinition);
        // Count previous number of leaves:
        fai.deepSort(prevFeatureList);
        CART prevCART = new FeatureVectorCART(fai.getTree(), fai);
        int prevNLeaves = 0;
        for (LeafNode leaf : prevCART.getLeafNodes()) {
            if (leaf != null && leaf.getNumberOfData() > 0)
                prevNLeaves++;
        }
        int iBestFeature = -1;
        double minGI = Double.POSITIVE_INFINITY;
        // Loop over all unused discrete features, and compute their Global Impurity
        for (int f=0; f<availableFeatures.length; f++) {
            int fi = availableFeatures[f];
            boolean featureAlreadyUsed = false;
            for (int i=0; i<prevFeatureList.length; i++) {
                if (prevFeatureList[i] == fi) {
                    featureAlreadyUsed = true;
                    break;
                }
            }
            if (featureAlreadyUsed) continue;
            newFeatureList[newFeatureList.length-1] = fi;
            fai.deepSort(newFeatureList);
            CART testCART = new FeatureVectorCART(fai.getTree(), fai);
            assert testCART.getRootNode().getNumberOfData() == trainingFeatures.length;
            List<LeafNode> leaves = new ArrayList<LeafNode>();
            for (LeafNode leaf : testCART.getLeafNodes()) {
                leaves.add(leaf);
            }
            double gi = computeGlobalImpurity(leaves);
            //System.out.println("Feature "+featureList.length+" using "+featureDefinition.getFeatureName(fi)+" yields GI "+gi);
            if (gi < minGI) {
                minGI = gi;
                iBestFeature = fi;
            }
        }
        newFeatureList[newFeatureList.length-1] = iBestFeature;
        fai.deepSort(newFeatureList);
        CART bestFeatureCart = new FeatureVectorCART(fai.getTree(), fai);
        int nLeaves = 0;
        for (LeafNode leaf : bestFeatureCart.getLeafNodes()) {
            if (leaf != null && leaf.getNumberOfData() > 0)
                nLeaves++;
        }
        // Stop criterion: if nLeaves is not substantially bigger than prevNLeaves, stop.
        if ((nLeaves-prevNLeaves)/(float)prevNLeaves < 0.01) {
            // less than one percent increase -- will not use this feature
            // stop growing the tree.
            return graphSoFar;
        }
        long featSelectedTime = System.currentTimeMillis();
        
        // Now walk through graphSoFar and bestFeatureCart in parallel,
        // and add the leaves of bestFeatureCart into graphSoFar in order
        // to enable clustering:
        Node fNode = bestFeatureCart.getRootNode();
        Node gNode = graphSoFar.getRootNode();
        
        List<DirectedGraphNode> newLeavesList = new ArrayList<DirectedGraphNode>();
        updateGraphFromTree((DecisionNode)fNode, (DirectedGraphNode) gNode, newLeavesList);
        DirectedGraphNode[] newLeaves = newLeavesList.toArray(new DirectedGraphNode[0]);
        System.out.printf("Level %2d: %25s (%5d leaves, gi=%7.2f -->", 
                newFeatureList.length, featureDefinition.getFeatureName(iBestFeature), newLeaves.length, minGI);

        float[][] deltaGI = new float[newLeaves.length-1][];
        for (int i=0; i<newLeaves.length-1; i++) {
            deltaGI[i] = new float[newLeaves.length-i-1];
            for (int j=i+1; j<newLeaves.length; j++) {
                deltaGI[i][j-i-1] = (float) computeDeltaGI(newLeaves[i], newLeaves[j]);
            }
        }
        int numLeavesLeft = newLeaves.length;
        
        // Now cluster the leaves
        float minDeltaGI, threshold;
        int bestPair1, bestPair2;
        do {
            //threshold = 100*(float)(Math.log(numLeavesLeft)-Math.log(numLeavesLeft-1));
            //threshold = (float)(Math.log(numLeavesLeft)-Math.log(numLeavesLeft-1));
            threshold = 0;
            //threshold = 0.01f;
            minDeltaGI = threshold; // if we cannot find any that is better, stop.
            bestPair1 = bestPair2 = -1;
            for (int i=0; i<newLeaves.length-1; i++) {
                if (newLeaves[i] == null) continue;
                for (int j=i+1; j<newLeaves.length; j++) {
                    if (newLeaves[j] == null) continue;
                    if (deltaGI[i][j-i-1] < minDeltaGI) {
                        bestPair1 = i;
                        bestPair2 = j;
                        minDeltaGI = deltaGI[i][j-i-1];
                    }
                }
            }
            //System.out.printf("NumLeavesLeft=%4d, threshold=%f, minDeltaGI=%f\n", numLeavesLeft, threshold, minDeltaGI);
            if (minDeltaGI < threshold) { // found something to merge
                mergeLeaves(newLeaves[bestPair1], newLeaves[bestPair2]);
                numLeavesLeft--;
                //System.out.println("Merged leaves "+bestPair1+" and "+bestPair2+" (deltaGI: "+minDeltaGI+")");
                newLeaves[bestPair2] = null;
                // Update deltaGI table:
                for (int i=0; i<bestPair2; i++) {
                    deltaGI[i][bestPair2-i-1] = Float.NaN;
                }
                for (int j=bestPair2+1; j<newLeaves.length; j++) {
                    deltaGI[bestPair2][j-bestPair2-1] = Float.NaN;
                }
                for (int i=0; i<bestPair1; i++) {
                    if (newLeaves[i] != null)
                        deltaGI[i][bestPair1-i-1] = (float) computeDeltaGI(newLeaves[i], newLeaves[bestPair1]);
                }
                for (int j=bestPair1+1; j<newLeaves.length; j++) {
                    if (newLeaves[j] != null)
                        deltaGI[bestPair1][j-bestPair1-1] = (float) computeDeltaGI(newLeaves[bestPair1], newLeaves[j]);
                }
            }
        } while (minDeltaGI < threshold);

        int nLeavesLeft = 0;
        List<LeafNode> survivors = new ArrayList<LeafNode>();
        for (int i=0; i<newLeaves.length; i++) {
            if (newLeaves[i] != null) {
                nLeavesLeft++;
                survivors.add((LeafNode)((DirectedGraphNode)newLeaves[i]).getLeafNode());
            }
        }
        
        long clusteredTime = System.currentTimeMillis();
        
        System.out.printf("%5d leaves, gi=%7.2f).", nLeavesLeft, computeGlobalImpurity(survivors));
        
        deltaGI = null;
        impurities.clear();
        
        float testDist = rmsDistanceTestData(graphSoFar);
        System.out.printf(" Distance test data: %5.2f",testDist);
        
        System.out.printf(" | fs %5dms, cl %5dms", (featSelectedTime-startTime), (clusteredTime-featSelectedTime));
        
        System.out.println();
        // Iteration step:
        return cluster(graphSoFar, newFeatureList);
        
    }

    private double computeGlobalImpurity(List<LeafNode> leaves) {
        double gi = 0;
        // Global Impurity measures the average distance of an instance
        // to the other instances in the same leaf.
        // Global Impurity is computed as follows:
        // GI = 1/N * sum(|l| * I(l)), where
        // N = total number of instances (feature vectors);
        // |l| = the number of instances in a leaf;
        // I(l) = the impurity of the leaf.
        int variant = 3;
        if (variant == 1) {
            for (LeafNode leaf : leaves) {
                gi += leaf.getNumberOfData() * computeImpurity(leaf);
            }
            gi /= trainingFeatures.length;
        } else if (variant == 2) {
            for (LeafNode leaf : leaves) {
                gi += computeImpurity(leaf); // more leaves is bad
            }
        } else if (variant == 3) {
            int numLeaves = 0;
            for (LeafNode leaf : leaves) {
                gi += leaf.getNumberOfData() * computeImpurity(leaf);
                numLeaves++;
            }
            gi /= trainingFeatures.length;
            //System.out.println("GI="+gi+", log="+Math.log(numLeaves));
            gi += Math.log(numLeaves); // each leaf adds one
        }
        return gi;
    }
    
    /**
     * The impurity of a leaf node is computed as follows:
     * I(l) = sqrt( 2/(|l|*(|l|-1)) * sum over all pairs(distance of pair) ),
     * where |l| = the number of instances in the leaf.
     * @param leaf
     * @return
     */
    private double computeImpurity(LeafNode leaf)
    {
        if (!(leaf instanceof FeatureVectorLeafNode))
            throw new IllegalArgumentException("Currently only feature vector leaf nodes are supported");
        if (impurities.containsKey(leaf)) return impurities.get(leaf);
        FeatureVectorLeafNode l = (FeatureVectorLeafNode) leaf;
        FeatureVector[] fvs = l.getFeatureVectors();
        int len = fvs.length;
        if (len < 2) return SINGLE_ITEM_IMPURITY;
        double impurity = 0;
        //System.out.println("Leaf has "+n+" items, computing "+(n*(n-1)/2)+" distances");
        for (int i=0; i<len; i++) {
            for (int j=i+1; j<len; j++) {
                impurity += dist.squaredDistance(fvs[i], fvs[j]);
            }
        }
        impurity *= 2./(len*(len-1));
        impurity = Math.sqrt(impurity);
        
        // Penalty for small leaves:
        //impurity += (float)SINGLE_ITEM_IMPURITY/(len*len);
        
        impurities.put(leaf, impurity);
        return impurity;
    }
    
    /**
     * The delta in global impurity that would be caused by merging the two given leaves
     * is computed as follows.
     * Delta GI = (|l1|+|l2|) * I(l1 united with l2) - |l1| * I(l1) - |l2| * I(l2)
     *          = 1/N*(|l1|+|l2|-1) * 
     *            (sum of all distances between items in l1 and items in l2
     *              - |l2| * I(l1) - |l1| * I(l2) )
     * where N = sum of all |l| = total number of instances in the tree,
     * |l| = number of instances in the leaf l 
     * @param dgn1
     * @param dgn2
     * @return
     */
    private double computeDeltaGI(DirectedGraphNode dgn1, DirectedGraphNode dgn2)
    {
        FeatureVectorLeafNode l1 = (FeatureVectorLeafNode) dgn1.getLeafNode();
        FeatureVectorLeafNode l2 = (FeatureVectorLeafNode) dgn2.getLeafNode();
        FeatureVector[] fv1 = l1.getFeatureVectors();
        FeatureVector[] fv2 = l2.getFeatureVectors();
        double deltaGI = 0;
        int variant = 2;
        if (variant == 1) {
            // Sum of all distances across leaf boundaries:
            for (int i=0; i<fv1.length; i++) {
                for (int j=0; j<fv2.length; j++) {
                    deltaGI += dist.squaredDistance(fv1[i], fv2[j]);
                }
            }
            deltaGI -= l2.getNumberOfData() * computeImpurity(l1);
            deltaGI -= l1.getNumberOfData() * computeImpurity(l2);
            deltaGI /= l1.getNumberOfData()+l2.getNumberOfData()-1;
            deltaGI /= trainingFeatures.length;
        } else if (variant == 2) {
            int len1 = l1.getNumberOfData();
            int len2 = l2.getNumberOfData();
            double imp1 = computeImpurity(l1);
            double imp2 = computeImpurity(l2);
            
            double imp12 = len1*(len1-1)/2*imp1*imp1 + len2*(len2-1)/2*imp2*imp2;
            // Sum of all distances across leaf boundaries:
            for (int i=0; i<fv1.length; i++) {
                for (int j=0; j<fv2.length; j++) {
                    imp12 += dist.squaredDistance(fv1[i], fv2[j]);
                }
            }
            imp12 *= 2./((len1+len2)*(len1+len2-1));
            imp12 = Math.sqrt(imp12);
            deltaGI = 1./trainingFeatures.length * ((len1+len2)*imp12 - len1*imp1 - len2*imp2);
            // Encourage small leaves to merge:
            double sizeEffect = 1 * (1./((len1+len2)*(len1+len2)) - 1./(len1*len1) - 1./(len2*len2));
            //System.out.println("len1="+len1+", len2="+len2+", sizeEffect="+sizeEffect+", deltaGI="+deltaGI);
            deltaGI += sizeEffect;
        } else if (variant == 3) {
            // Sum of all distances across leaf boundaries:
            for (int i=0; i<fv1.length; i++) {
                for (int j=0; j<fv2.length; j++) {
                    deltaGI += dist.squaredDistance(fv1[i], fv2[j]);
                }
            }
            deltaGI -= l2.getNumberOfData() * computeImpurity(l1);
            deltaGI -= l1.getNumberOfData() * computeImpurity(l2);
            deltaGI /= l1.getNumberOfData()+l2.getNumberOfData()-1;
            deltaGI /= trainingFeatures.length;
            deltaGI -= 1; // one leaf less
        }
        return deltaGI;
    }
    
    
    private void mergeLeaves(DirectedGraphNode dgn1, DirectedGraphNode dgn2)
    {
        // Copy all data from dgn2 into dgn1
        FeatureVectorLeafNode l1 = (FeatureVectorLeafNode) dgn1.getLeafNode();
        FeatureVectorLeafNode l2 = (FeatureVectorLeafNode) dgn2.getLeafNode();
        FeatureVector[] fv1 = l1.getFeatureVectors();
        FeatureVector[] fv2 = l2.getFeatureVectors();
        FeatureVector[] newFV = new FeatureVector[fv1.length+fv2.length];
        System.arraycopy(fv1, 0, newFV, 0, fv1.length);
        System.arraycopy(fv2, 0, newFV, fv1.length, fv2.length);
        l1.setFeatureVectors(newFV);
        // then update all mother/daughter relationships
        Set<Node> dgn2Mothers = new HashSet<Node>(dgn2.getMothers());
        for (Node mother : dgn2Mothers) {
            if (mother instanceof DecisionNode) {
                DecisionNode dm = (DecisionNode) mother;
                dm.replaceDaughter(dgn1, dgn2.getNodeIndex(mother));
            } else if (mother instanceof DirectedGraphNode) {
                DirectedGraphNode gm = (DirectedGraphNode) mother;
                gm.setLeafNode(dgn1);
            }
            dgn2.removeMother(mother);
        }
        dgn2.setLeafNode(null);
        l2.setMother(null, 0);
        // and remove impurity entries:
        try {
            impurities.remove(l1);
            impurities.remove(l2);
        } catch (NullPointerException e) {
            e.printStackTrace();
            System.err.println("Impurities: "+impurities+", l1:"+l1+", l2:"+l2);
        }
    }
    
    
    private void updateGraphFromTree(DecisionNode treeNode, DirectedGraphNode graphNode, List<DirectedGraphNode> newLeaves)
    {
        int treeFeatureIndex = treeNode.getFeatureIndex();
        int treeNumDaughters = treeNode.getNumberOfDaugthers();
        DecisionNode graphDecisionNode = graphNode.getDecisionNode();
        if (graphDecisionNode != null) {
            // Sanity check: the two must be aligned: same feature, same number of children
            int graphFeatureIndex = graphDecisionNode.getFeatureIndex();
            assert treeFeatureIndex == graphFeatureIndex : "Tree indices out of sync!";
            assert treeNumDaughters == graphDecisionNode.getNumberOfDaugthers() : "Tree structure out of sync!";
            // OK, now recursively call ourselves for all daughters
            for (int i=0; i<treeNumDaughters; i++) {
                // We expect the next tree node to be a decision node (unless it is an empty node),
                // because the level just above the leaves does not exist in graph yet.
                Node nextTreeNode = treeNode.getDaughter(i);
                if (nextTreeNode == null) continue;
                else if (nextTreeNode instanceof LeafNode) {
                    assert ((LeafNode)nextTreeNode).getNumberOfData() == 0;
                    continue;
                }
                assert nextTreeNode instanceof DecisionNode;
                DirectedGraphNode nextGraphNode = (DirectedGraphNode) graphDecisionNode.getDaughter(i);
                updateGraphFromTree((DecisionNode)nextTreeNode, nextGraphNode, newLeaves);
            }
        } else {
            // No structure in graph yet which corresponds to tree.
            // This is what we actually want to do.
            if (featureDefinition.isByteFeature(treeFeatureIndex)) {
                graphDecisionNode = new DecisionNode.ByteDecisionNode(treeFeatureIndex, treeNumDaughters, featureDefinition);
            } else {
                assert featureDefinition.isShortFeature(treeFeatureIndex) : "Only support byte and short features";
                graphDecisionNode = new DecisionNode.ShortDecisionNode(treeFeatureIndex, treeNumDaughters, featureDefinition);
            }
            assert treeNumDaughters == graphDecisionNode.getNumberOfDaugthers();
            graphNode.setDecisionNode(graphDecisionNode);
            for (int i=0; i<treeNumDaughters; i++) {
                // we expect the next tree node to be a leaf node
                LeafNode nextTreeNode = (LeafNode) treeNode.getDaughter(i);
                // Now create the new daughter number i of graphDecisionNode.
                // It is a DirectedGraphNode containing no decision tree but
                // a leaf node, which is itself a DirectedGraphNode with no
                // decision node but a leaf node:
                if (nextTreeNode != null && nextTreeNode.getNumberOfData() > 0) {
                    DirectedGraphNode daughterLeafNode = new DirectedGraphNode(null, nextTreeNode);
                    DirectedGraphNode daughterNode = new DirectedGraphNode(null, daughterLeafNode);
                    graphDecisionNode.addDaughter(daughterNode);
                    newLeaves.add(daughterLeafNode);
                } else {
                    graphDecisionNode.addDaughter(null);
                }
            }
        }
    }


    private float rmsDistanceTestData(DirectedGraph graph)
    {
        float avgDist = 0;
        for (int i=0; i<testFeatures.length; i++) {
            FeatureVector[] leafData = (FeatureVector[]) graph.interpret(testFeatures[i]);
            float oneDist = 0;
            for (int j=0; j<leafData.length; j++) {
                oneDist += dist.squaredDistance(testFeatures[i], leafData[j]);
            }
            oneDist /= leafData.length;
            oneDist = (float) Math.sqrt(oneDist);
            avgDist += oneDist;
        }
        avgDist /= testFeatures.length;
        return avgDist;
    }
    
    
    
    
    private void debugOut(DirectedGraph graph)
    {
        for (Iterator<Node> it = graph.getNodeIterator(); it.hasNext(); ) {
            Node next = it.next();
            debugOut(next);
        }
    }
    
    private void debugOut(CART graph)
    {
        Node root = graph.getRootNode();
        debugOut(root);
    }

    private void debugOut(Node node)
    {
        if (node instanceof DirectedGraphNode)
            debugOut((DirectedGraphNode)node);
        else if (node instanceof LeafNode)
            debugOut((LeafNode)node);
        else
            debugOut((DecisionNode)node);
    }
    
    private void debugOut(DirectedGraphNode node)
    {
        System.out.println("DGN");
        if (node.getLeafNode() != null) debugOut(node.getLeafNode());
        if (node.getDecisionNode() != null) debugOut(node.getDecisionNode());
    }
    
    private void debugOut(LeafNode node)
    {
        System.out.println("Leaf: "+node.getDecisionPath());
    }
    
    private void debugOut(DecisionNode node)
    {
        System.out.println("DN with "+node.getNumberOfDaugthers()+" daughters: "+node.toString());
        for (int i=0; i<node.getNumberOfDaugthers(); i++) {
            Node daughter = node.getDaughter(i);
            if (daughter == null) System.out.println("null");
            else debugOut(daughter);
        }
    }

}