/**
 * Copyright (c) 2013 Cloudant, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.cloudant.sync.datastore;

import com.cloudant.sync.util.AbstractTreeNode;
import com.google.common.base.Preconditions;

import java.util.*;

/**
 * <p>Describes the document tree for a single
 * document within the datastore.</p>
 *
 * <p>A document within a {@link Datastore} is actually a collection of trees
 * of revisions. These trees describe the history of the document, and are the
 * core of the MVCC data model which allows data to be safely replicated
 * between datastores. Replication consists of copying parts of the tree
 * in the source database that are not present in the target database to the
 * target database.</p>
 *
 * <p>Commonly, there will be a single tree with no branches, just
 * a straight history. This can be represented as follows, abbreviating
 * revision IDs to their generation numbers:</p>
 *
 * <pre>
 *     1 -> 2 -> 3
 * </pre>
 *
 * <p>A full revision ID looks like {@code 1-aedlfksenef}, that is the
 * generation number combined with an opaque
 * string which identifies this revision vs other revisions with the same
 * generation number (see below for when this occurs).</p>
 *
 * <p>A document can gain branches in its tree. Consider the following sequence
 * of events:</p>
 *
 * <ol>
 *     <li>A document is created in datastore A and replicated to datastore
 *     B.</li>
 *     <li>The document is edited in both A and B.</li>
 *     <li>Datastore B is then replicated back to A, bringing with it the
 *     changes in the document.</li>
 * </ol>
 *
 * <p>As described above, replication merges the parts of a document tree not
 * in the target datastore into the target datastore. This means we now have
 * a branched tree containing the edits from both A and B:</p>
 *
 * <pre>
 *     1 ->  2  -> 3 -> 4
 *       \-> 2^ -> 3^
 * </pre>
 *
 * <p>At this point we have a conflicted document: two or more branches
 * terminating in leaf nodes which are not deleted, or <em>active</em>.</p>
 *
 * <p>In this case, to resolve the conflict the application would need to:</p>
 *
 * <ol>
 *     <li>Call {@link Datastore#getDocument(String, String)} to get the
 *     revisions {@code 4} and {@code 3^}.</li>
 *     <li>Merge these documents together in some way.</li>
 *     <li>Save a new revision in the {@code 4} branch using
 *     {@link Datastore#updateDocument(String, String, DocumentBody)}</li>
 *     <li>Delete the {@code 3^} revision using
 *     {@link Datastore#deleteDocument(String, String)}.</li>
 * </ol>
 *
 * <p>This process leaves us still with two branches. Because only one
 * terminates in an active leaf, however, the document is no longer
 * conflicted:</p>
 *
 * <pre>
 *     1 ->  2  -> 3  -> 4 -> 5
 *       \-> 2^ -> 3^ -> (4^ deleted)
 * </pre>
 *
 * <p>Finally, a document may have multiple document trees. This can happen if
 * a document with the same ID is created in two datastores, and subsequently
 * these two datastores are replicated:</p>
 *
 * <pre>
 *     1  -> 2  -> 3
 *     1* -> 2* -> 3* -> 4*
 *       \-> 2^ -> 3^
 * </pre>
 *
 * <p>As an aside, this document has three conflicting revisions, which should
 * be resolved as described above.</p>
 *
 * <p>There can obviously be more than two roots, as a many datastores can
 * replicate together.</p>
 *
 * <p>At this point, it's possible to note that
 * this class might better be called {@code DBObjectForest}. However, it's called
 * {@code DocumentRevisionTree} because Tree is the common terminology used for the
 * structure inside a document. As each individual tree's first revision is
 * {@code 1-something}, one could think of the document as a single tree with
 * an implied {@code 0-something} revision at the root.
 * </p>
 *
 * <p>To construct a {@code DocumentRevisionTree}, first create an empty tree. Then
 * call {@link DocumentRevisionTree#add(DocumentRevision)} with each {@code DocumentRevision} in
 * ascending generation order. For {@code DocumentRevision}s with the same generation,
 * the order they are added in should not matter; a branch will be created
 * automatically from the {@link DocumentRevision#getParent()}
 * property of each {@code DocumentRevision}. This implies that if a {@code DocumentRevision}
 * that is not the root of a tree (that is, it has a parent), the parent must
 * be added first so the tree is constructed correctly.</p>
 *
 * <p>When a complete tree has been constructed, it's possible to work out
 * things like the complete set of non-deleted leaf revisions (that is, the
 * conflicted revisions for the document) and what is the current winning
 * revision of the document.</p>
 *
 * <p><strong>WARNING:</strong> conflict resolution is coming in the next
 * release, where we'll be adding methods to {@code Datastore} to help
 * resolving conflicts:</p>
 *
 * <ul>
 *     <li>Get the IDs of all conflicted documents within the datastore.</li>
 *     <li>Get a list of all current revisions for a given document, so they
 *     can be merged to resolve the conflict.</li>
 * </ul>
 */
@SuppressWarnings("serial")
public class DocumentRevisionTree {

    /**
     * <p>A map of sequence number to {@code DocumentRevisionNode} for each root
     * of the document's forest.</p>
     */
    private Map<Long, DocumentRevisionNode> roots = new HashMap<Long, DocumentRevisionNode>();

    /**
     * A list of all leaf revisions in this document. More than one active
     * revision in this list indicates a conflicted document.
     */
    private List<DocumentRevisionNode> leafs = new ArrayList<DocumentRevisionNode>();

    // All the DocumentRevisionTree revisions from all the trees.
    // Map: sequence number -> DocumentRevisionNode
    private Map<Long, DocumentRevisionNode> sequenceMap = new TreeMap<Long, DocumentRevisionNode>();

    /**
     * <p>Construct an empty tree.</p>
     */
    public DocumentRevisionTree() {}

    /**
     * <p>Construct a tree with a single root.</p>
     *
     * @param documentRevision a root of the revision tree
     */
    public DocumentRevisionTree(DocumentRevision documentRevision) {
        addRootDBObject(documentRevision);
    }

    /**
     * <p>Adds a new {@link DocumentRevision} to the document.</p>
     *
     * <p>The {@code DocumentRevision}'s parent, if there is one, must already be in
     * the document tree.</p>
     *
     * @param documentRevision the {@code DocumentRevision} to add
     */
    public DocumentRevisionTree add(DocumentRevision documentRevision) {
        Preconditions.checkArgument(!sequenceMap.containsKey(documentRevision.getSequence()),
                "The revision must not be added to the tree before.");

        if(documentRevision.getParent() <= 0) {
            addRootDBObject(documentRevision);
        } else {
            addNode(documentRevision);
        }
        return this;
    }

    private void addRootDBObject(DocumentRevision documentRevision) {
        Preconditions.checkArgument(documentRevision.getParent() <= 0,
                "The added root DocumentRevision must be a valid root revision.");

        DocumentRevisionNode rootNode = new DocumentRevisionNode(documentRevision);
        this.roots.put(documentRevision.getSequence(), rootNode);
        this.leafs.add(rootNode);
        this.sequenceMap.put(documentRevision.getSequence(), rootNode);
    }

    private void addNode(DocumentRevision documentRevision) {
        long parentSequence = documentRevision.getParent();
        Preconditions.checkArgument(sequenceMap.containsKey(parentSequence),
            "The given revision's parent must be in the tree already.");

        DocumentRevisionNode parent = sequenceMap.get(parentSequence);
        DocumentRevisionNode newNode = new DocumentRevisionNode(documentRevision);
        parent.addChild(newNode);

        if(this.leafs.contains(parent)) {
            this.leafs.remove(parent);
        }
        this.leafs.add(newNode);
        sequenceMap.put(newNode.getData().getSequence(), newNode);
    }

    /**
     * <p>Returns the {@link DocumentRevision} for a document ID and revision ID.</p>
     * @param id document ID
     * @param rev revision ID
     * @return the {@code DocumentRevision} for the document and revision ID
     */
    public DocumentRevision lookup(String id, String rev) {
        for(DocumentRevisionNode n : sequenceMap.values()) {
            if(n.getData().getId().equals(id) && n.getData().getRevision().equals(rev)) {
                return n.getData();
            }
        }
        return null;
    }

    /**
     * <p>Returns the distance a revision is down the branch it's on.</p>
     *
     * @param sequence sequence number to identify the revision
     * @return the distance down the branch for the sequence number, {@code -1}
     *      if the sequence number isn't present in the document.
     */
    public int depth(long sequence) {
        if(sequenceMap.containsKey(sequence)) {
            return sequenceMap.get(sequence).depth();
        } else {
            return -1;
        }
    }

    /**
     * <p>Returns the child with a given revision ID of a parent
     * {@link DocumentRevision}.</p>
     *
     * @param parentNode parent {@code DocumentRevision}
     * @param childRevision revision to look for in child nodes
     * @return the child with a given revision ID of a {@code DocumentRevision}.
     */
    public DocumentRevision lookupChildByRevId(DocumentRevision parentNode, String childRevision) {
        Preconditions.checkNotNull(parentNode, "Parent node must not be null.");
        Preconditions.checkArgument(sequenceMap.containsKey(parentNode.getSequence()),
                "The given parent DocumentRevision must be in the tree.");

        DocumentRevisionNode p = sequenceMap.get(parentNode.getSequence());
        Iterator i = p.iterateChildren();
        while(i.hasNext()) {
            DocumentRevisionNode n = (DocumentRevisionNode) i.next();
            if(n.getData().getRevision().equals(childRevision)) {
                return n.getData();
            }
        }
        return null;
    }

    /**
     * <p>Returns a {@link DocumentRevision} from this {@code DocumentRevisionTree} with
     * a particular sequence number.</p>
     *
     * @param sequence sequence number of the {@code DocumentRevision}
     * @return the {@code DocumentRevision} with the given sequence number,
     *     null if no {@code DocumentRevision} has the given sequence number.
     */
    public DocumentRevision bySequence(long sequence) {
        return sequenceMap.containsKey(sequence) ? sequenceMap.get(sequence).getData() : null;
    }

    /**
     * <p>Returns whether this document is conflicted.</p>
     *
     * @return true if there is more than one branch terminated by an active
     *      leaf revision, otherwise false
     */
    public boolean hasConflicts() {
        int count = 0;
        for(DocumentRevisionNode n : leafs) {
            if(!n.getData().isDeleted()) {
                count ++;
                if(count > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * <p>Returns the root revisions of this document in a mapping of sequence
     * number to {@link com.cloudant.sync.datastore.DocumentRevisionTree.DocumentRevisionNode}.</p>
     * @return  the root revisions of this document in a map
     */
    public Map<Long, DocumentRevisionNode> roots() {
        return roots;
    }


    /**
     * <p>Returns the root revision of this document with a given sequence number.
     * </p>
     * @return  the root revision of this document with a given sequence number
     */
    public DocumentRevisionNode root(long sequence) {
        return roots.get(sequence);
    }

    /**
     * <p>Returns a list of the {@link com.cloudant.sync.datastore.DocumentRevisionTree.DocumentRevisionNode}s which are leaf revisions
     * of the branches in this document</p>
     * @return list of leaf {@code DocumentRevisionNode}s
     */
    public List<DocumentRevisionNode> leafs() {
        return leafs;
    }

    /**
     * <p>Returns the revision IDs for the leaf revisions of this document.</p>
     *
     * @return the set of revision IDs for the leaf revisions of this document.
     */
    public Set<String> leafRevisionIds() {
        Set<String> res = new HashSet<String>();
        for(DocumentRevisionNode obj : leafs()) {
            res.add(obj.getData().getRevision());
        }
        return res;
    }

    /**
     * <p>Returns the leaf revisions</p>
     *
     * @return the set of the DocumentRevision
     */
    public List<DocumentRevision> leafRevisions() {
        List<DocumentRevision> res = new ArrayList<DocumentRevision>();
        for(DocumentRevisionNode obj : leafs()) {
            res.add(obj.getData());
        }
        return res;
    }

    /**
     * <p>Returns the {@link DocumentRevision} that is the current winning revision
     * for this {@code DocumentRevisionTree}.</p>
     * @return the {@link DocumentRevision} that is the current winning revision
     * for this {@code DocumentRevisionTree}.
     */
    public DocumentRevision getCurrentRevision() {
        for(DocumentRevisionNode n : leafs) {
            if(n.getData().isCurrent()) {
                return n.getData();
            }
        }
        throw new IllegalStateException("No current revision founded.");
    }

    /**
     * <p>Returns the path (list of {@code DocumentRevision}s) from the revision with
     * the given sequence number to the root of the tree containing the
     * revision.</p>
     *
     * <p>The list of revisions is in order of appearance in the tree, where
     * the first revision is the given {@code DocumentRevision} and the last revision
     * is the root of the tree.</p>
     *
     * @param sequence sequence of the starting revision
     * @return list of {@code DocumentRevision}s from the revision with {@code sequence}
     * to the root of the revision's tree.
     */
    public List<DocumentRevision> getPathForNode(long sequence) {
        Preconditions.checkArgument(sequenceMap.containsKey(sequence),
                "DocumentRevision for that sequence must be in the tree already.");
        DocumentRevisionNode l = sequenceMap.get(sequence);
        List<DocumentRevision> r = new LinkedList<DocumentRevision>();
        while(l != null) {
            r.add(l.getData());
            if(l.getData().getParent() > 0) {
                l = sequenceMap.get(l.getData().getParent());
            } else {
                l = null;
            }
        }

        return r;
    }

    /**
     * <p>Returns the path as a list of revision IDs from the revision with
     * the given sequence number to the root of the tree containing the
     * revision.</p>
     *
     * @param sequence sequence of the starting revision
     * @return list of revision IDs from the starting revision to the root of
     *   that revision's tree.
     */
    public List<String> getPath(long sequence) {
        List<DocumentRevision> objects = getPathForNode(sequence);
        List<String> res = new ArrayList<String>();
        for(DocumentRevision object : objects) {
            res.add(object.getRevision());
        }
        return res;
    }

    /**
     * <p>A node in a document's revision tree history.</p>
     */
    public static class DocumentRevisionNode extends AbstractTreeNode<DocumentRevision>
            implements Comparable<AbstractTreeNode<DocumentRevision>> {

        public DocumentRevisionNode(DocumentRevision documentRevision) {
            super(documentRevision);
        }

        @Override
        public int compareTo(AbstractTreeNode<DocumentRevision> o) {
            return getData().compareTo(o.getData());
        }

        @Override
        public boolean equals(Object that) {
            if (this == that)
                return true;
            if (that == null)
                return false;
            if (getClass() != that.getClass())
                return false;
            AbstractTreeNode<DocumentRevision> other = (AbstractTreeNode<DocumentRevision>) that;
            return this.getData().getSequence() == other.getData().getSequence();
        }

        @Override
        public int hashCode() {
            return Long.valueOf(this.getData().getSequence()).hashCode();
        }
    }
}
