/*
 * Copyright 2010-2014 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.invoice.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ning.billing.invoice.api.InvoiceItem;
import com.ning.billing.invoice.api.InvoiceItemType;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

/**
 * Tree of invoice items for a given account.
 *
 * <p>It contains a map of <tt>SubscriptionItemTree</tt> and the logic is executed independently for all items
 * associated to a given subscription. That also means that invoice item adjustment which cross subscriptions
 * can't be correctly handled when they compete with other forms of adjustments.
 *
 * <p>The class is not thread safe, there is no such use case today, and there is a lifecyle to respect:
 * <ul>
 * <li>Add existing invoice items
 * <li>Build the tree,
 * <li>Merge the proposed list
 * <li>Retrieves final list
 * <ul/>
 */
public class AccountItemTree {

    private final UUID accountId;
    private final Map<UUID, SubscriptionItemTree> subscriptionItemTree;
    private final List<InvoiceItem> allExistingItems;

    private boolean isBuilt;

    public AccountItemTree(final UUID accountId) {
        this.accountId = accountId;
        this.subscriptionItemTree = new HashMap<UUID, SubscriptionItemTree>();
        this.isBuilt = false;
        this.allExistingItems = new LinkedList<InvoiceItem>();
    }

    /**
     * build the subscription trees after they have been populated with existing items on disk
     */
    public void build() {
        Preconditions.checkState(!isBuilt);
        for (SubscriptionItemTree tree : subscriptionItemTree.values()) {
            tree.build();
        }
        isBuilt = true;
    }
    /**
     * Populate tree from existing items on disk
     *
     * @param existingItem an item read on disk
     */
    public void addExistingItem(final InvoiceItem existingItem) {

        Preconditions.checkState(!isBuilt);
        if (existingItem.getInvoiceItemType() != InvoiceItemType.RECURRING &&
            existingItem.getInvoiceItemType() != InvoiceItemType.REPAIR_ADJ &&
            existingItem.getInvoiceItemType() != InvoiceItemType.FIXED &&
            existingItem.getInvoiceItemType() != InvoiceItemType.ITEM_ADJ) {
            return;
        }

        allExistingItems.add(existingItem);

        final UUID subscriptionId  = getSubscriptionId(existingItem, allExistingItems);
        Preconditions.checkNotNull(subscriptionId);

        if (!subscriptionItemTree.containsKey(subscriptionId)) {
            subscriptionItemTree.put(subscriptionId, new SubscriptionItemTree(subscriptionId));
        }
        final SubscriptionItemTree tree = subscriptionItemTree.get(subscriptionId);
        tree.addItem(existingItem);
    }

    /**
     * Rebuild the new tree by merging current on-disk existing view with new proposed list.
     *
     * @param proposedItems list of proposed item that should be merged with current existing view
     */
    public void mergeWithProposedItems(final List<InvoiceItem> proposedItems) {

        for (SubscriptionItemTree tree : subscriptionItemTree.values()) {
            tree.flatten(true);
        }
        isBuilt = true;

        for (InvoiceItem item : proposedItems) {
            final UUID subscriptionId  = getSubscriptionId(item, null);
            SubscriptionItemTree tree = subscriptionItemTree.get(subscriptionId);
            if (tree == null) {
                tree = new SubscriptionItemTree(subscriptionId);
                subscriptionItemTree.put(subscriptionId, tree);
            }
            tree.mergeProposedItem(item);
        }

        for (SubscriptionItemTree tree : subscriptionItemTree.values()) {
            tree.buildForMerge();
        }
    }

    /**
     *
     * @return the resulting list of items that should be written to disk
     */
    public List<InvoiceItem> getResultingItemList() {
        final List<InvoiceItem> result = new ArrayList<InvoiceItem>();
        for (SubscriptionItemTree tree : subscriptionItemTree.values()) {
            final List<InvoiceItem> simplifiedView = tree.getView();
            if (simplifiedView.size() > 0) {
                result.addAll(simplifiedView);
            }
        }
        return result;
    }

    public UUID getAccountId() {
        return accountId;
    }

    private UUID getSubscriptionId(final InvoiceItem item, final List<InvoiceItem> allItems) {
        if (item.getInvoiceItemType() == InvoiceItemType.RECURRING ||
            item.getInvoiceItemType() == InvoiceItemType.FIXED) {
            return item.getSubscriptionId();
        } else {
            final InvoiceItem linkedItem  = Iterables.tryFind(allItems, new Predicate<InvoiceItem>() {
                @Override
                public boolean apply(final InvoiceItem input) {
                    return item.getLinkedItemId().equals(input.getId());
                }
            }).get();
            return linkedItem.getSubscriptionId();
        }
    }
}