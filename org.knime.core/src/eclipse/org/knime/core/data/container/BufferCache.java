/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 *
 * History
 *   14 Feb 2019 (Marc): created
 */
package org.knime.core.data.container;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import org.knime.core.data.container.Buffer.Lifecycle;
import org.knime.core.util.LRUCache;

/**
 * A data structure that manages which tables (i.e., {@link List} of {@link BlobSupportDataRow}) to keep in memory. The
 * cache has two layers: an upper layer for tables that are guaranteed to be kept in memory and a lower level for tables
 * that are cleared for garbage collection. Tables in the lower level are attempted to be kept in-memory for as long as
 * they've recently been used, but are guaranteed to be dropped before KNIME runs out of memory. The cache itself does
 * not take care of when and how tables are flushed to disk and cleared for garbage collection, but makes sure that no
 * tables are cleared for garbage collection before they have been flushed to disk. How this cache is used by the
 * {@link Buffer} class is specified by means of a {@link Lifecycle}.
 *
 * @author Marc Bux, KNIME GmbH, Berlin, Germany
 */
final class BufferCache {

    /**
     * A number that determines how many tables are kept in the soft-references LRU cache before being weak-referenced.
     */
    static final int LRU_CACHE_SIZE = 32;

    /**
     * A map of hard references to tables held in this cache. Caution: the garbage collector will not clear these
     * automatically. We use the buffer itself as key, since multiple buffers can have the same id. The Map has to have
     * weak keys such that unreferenced buffers can be garbage-collected if we forget to clear them.
     */
    private Map<Buffer, List<BlobSupportDataRow>> m_hardMap = new WeakHashMap<>();

    /**
     * An LRU-cache of soft references to tables held in this cache. Note that soft references also keep track of when
     * they were last accessed. When memory becomes scarce, the garbage collector should clear weak-referenced tables
     * first and then proceed with soft-referenced tables in the order in which they were least recently used.
     */
    private LRUCache<Buffer, SoftReference<List<BlobSupportDataRow>>> m_LRUCache =
        new LRUCache<>(LRU_CACHE_SIZE, LRU_CACHE_SIZE);

    /** A map of weak references to tables evicted from the LRU cache. */
    private Map<Buffer, WeakReference<List<BlobSupportDataRow>>> m_weakCache = new WeakHashMap<>();

    /**
     * Puts a fully-read table into the cache, from where it can be retrieved but no longer modified.
     *
     * @param buffer the buffer which the table is associated with
     * @param list a fully read table
     */
    synchronized void put(final Buffer buffer, final List<BlobSupportDataRow> list) {
        /** disallow modification */
        List<BlobSupportDataRow> undmodifiableList = Collections.unmodifiableList(list);
        m_hardMap.put(buffer, undmodifiableList);
        /**
         * We already fill the soft cache here to keep track of how recently the table has been used. Note that soft and
         * weak references won't be cleared while there is still a hard reference on the object.
         */
        m_LRUCache.put(buffer, new SoftReference<List<BlobSupportDataRow>>(undmodifiableList));
        m_weakCache.put(buffer, new WeakReference<List<BlobSupportDataRow>>(undmodifiableList));
    }

    /**
     * Clear the table associated with a buffer for garbage collection. From this point onward, the garbage collector
     * may at any time discard the in-memory representation of the table. Therefore, this method should only ever be
     * called after the table has been flushed to disk.
     *
     * @param buffer the buffer which table that is to be cleared for garbage collection is associated with
     */
    synchronized void clearForGarbageCollection(final Buffer buffer) {
        assert buffer.isFlushedToDisk();
        m_hardMap.remove(buffer);
    }

    /**
     * Checks whether the cache holds a hard reference on the table associated with a given buffer. Note that if this
     * method return <code>false</code>, the table might still be in the cache, but cleared for garbage collection.
     *
     * @param buffer the buffer which the to-be-checked table is associated with
     * @return <code>true</code> iff the associated table is held in the cache and not cleared for garbage collection
     */
    synchronized boolean contains(final Buffer buffer) {
        WeakReference<List<BlobSupportDataRow>> weakRef = m_weakCache.get(buffer);
        if (weakRef != null) {
            return weakRef.get() != null;
        }
        return false;
    }

    /**
     * Retrieve the table associated with a buffer from the cache.
     *
     * @param buffer the buffer which the to-be-retrieved table is associated with
     * @return a table represented as a list of datarows, if such a table is present in the cache
     */
    synchronized Optional<List<BlobSupportDataRow>> get(final Buffer buffer) {
        /** Update recent access in LRU cache and soft reference. */
        SoftReference<List<BlobSupportDataRow>> softRef = m_LRUCache.get(buffer);
        if (softRef != null) {
            List<BlobSupportDataRow> list = softRef.get();
            if (list != null) {
                return Optional.of(list);
            }
        }
        /**
         * If we did not find the table in the LRU cache, look for it in the map of weak references. Weak references
         * won't be dropped while a hard reference on the list still exists, so we don't have to check the map of hard
         * references.
         */
        WeakReference<List<BlobSupportDataRow>> weakRef = m_weakCache.get(buffer);
        if (weakRef != null) {
            List<BlobSupportDataRow> list = weakRef.get();
            if (list != null) {
                /** Make sure to put the accessed table back into the LRU cache. */
                m_LRUCache.put(buffer, new SoftReference<List<BlobSupportDataRow>>(list));
                return Optional.of(list);
            } else {
                /** Table has been garbage collected; key and empty references can be removed. */
                invalidate(buffer);
            }
        }
        return Optional.empty();
    }

    /**
     * Invalidate the table associated with a buffer, i.e., completely remove any trace of it from the cache.
     *
     * @param buffer the buffer which the to-be-invalidated table is associated with
     */
    synchronized void invalidate(final Buffer buffer) {
        m_hardMap.remove(buffer);
        m_LRUCache.remove(buffer);
        m_weakCache.remove(buffer);
    }

}
