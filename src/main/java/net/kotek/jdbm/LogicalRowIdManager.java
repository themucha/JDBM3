/*******************************************************************************
 * Copyright 2010 Cees De Groot, Alex Boisvert, Jan Kotek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package net.kotek.jdbm;

import java.io.IOException;

/**
 * This class manages the linked lists of logical rowid pages.
 */
final class LogicalRowIdManager {
    // our record file and associated page manager
    private final RecordFile file;
    private final PageManager pageman;
    private final FreeLogicalRowIdPageManager freeman;
    static final short ELEMS_PER_PAGE = (short) ((Storage.BLOCK_SIZE - Magic.PAGE_HEADER_SIZE) / Magic.PhysicalRowId_SIZE);


    /**
     * Creates a log rowid manager using the indicated record file and page manager
     */
    LogicalRowIdManager(RecordFile file, PageManager pageman, FreeLogicalRowIdPageManager freeman) throws IOException {
        this.file = file;
        this.pageman = pageman;
        this.freeman = freeman;
    }

    /**
     * Creates a new logical rowid pointing to the indicated physical id
     *
     * @param physloc physical location to point to
     * @return logical recid
     */
    long insert(final long physloc) throws IOException {
        // check whether there's a free rowid to reuse
        long retval = freeman.get();
        if (retval == 0) {
            // no. This means that we bootstrap things by allocating
            // a new translation page and freeing all the rowids on it.
            long firstPage = pageman.allocate(Magic.TRANSLATION_PAGE);
            short curOffset = Magic.PAGE_HEADER_SIZE;
            for (int i = 0; i < ELEMS_PER_PAGE; i++) {
                freeman.put(Location.toLong(-firstPage, curOffset));
                curOffset += Magic.PhysicalRowId_SIZE;
            }

            retval = freeman.get();
            if (retval == 0) {
                throw new Error("couldn't obtain free translation");
            }
        }
        // write the translation.
        update(retval, physloc);
        return retval;
    }

    /**
     * Insert at forced location, use only for defragmentation !!
     *
     * @param logicalRowId
     * @param physLoc
     * @throws IOException
     */
    void forceInsert(final long logicalRowId, final long physLoc) throws IOException {
        if (fetch(logicalRowId) != 0)
            throw new Error("can not forceInsert, record already exists: " + logicalRowId);

        update(logicalRowId, physLoc);
    }


    /**
     * Releases the indicated logical rowid.
     */
    void delete(final long logicalrowid) throws IOException {
        //zero out old location, is needed for defragmentation
        final long block = -Location.getBlock(logicalrowid);
        final BlockIo xlatPage = file.get(block);
        xlatPage.pageHeaderSetLocation(Location.getOffset(logicalrowid), 0);
        file.release(block, true);
        freeman.put(logicalrowid);
    }

    /**
     * Updates the mapping
     *
     * @param logicalrowid The logical rowid
     * @param physloc   The physical rowid
     */
    void update(final long logicalrowid, final long physloc) throws IOException {

        final long block = -Location.getBlock(logicalrowid);
        final BlockIo xlatPage = file.get(block);
        xlatPage.pageHeaderSetLocation(Location.getOffset(logicalrowid), physloc);
        file.release(block, true);
    }

    /**
     * Returns a mapping
     *
     * @param logicalrowid The logical rowid
     * @return The physical rowid, 0 if does not exist
     */
    long fetch(long logicalrowid) throws IOException {
        final long block = -Location.getBlock(logicalrowid);
        final long last = pageman.getLast(Magic.TRANSLATION_PAGE);
        if (last - 1 > block)
            return 0;

        final short offset = Location.getOffset(logicalrowid);

        final BlockIo xlatPage = file.get(block);
        final long ret =  xlatPage.pageHeaderGetLocation(offset);


        file.release(block, false);
        return ret;
    }

    void commit() throws IOException {
        freeman.commit();
    }

    void rollback() throws IOException {
        freeman.rollback();
    }


}
