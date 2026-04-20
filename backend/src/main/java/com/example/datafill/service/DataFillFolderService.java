package com.example.datafill.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.datafill.dto.DataFillFolderNode;
import com.example.datafill.dto.FolderReorderItem;
import com.example.datafill.entity.DataFillFolder;
import com.example.datafill.entity.DataFillForm;
import com.example.datafill.mapper.DataFillFolderMapper;
import com.example.datafill.mapper.DataFillFormMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DataFillFolderService {

    public static final String UNCATEGORIZED_FOLDER_ID = "__uncategorized__";

    private final DataFillFolderMapper folderMapper;
    private final DataFillFormMapper formMapper;

    public List<DataFillFolder> listAllFolders() {
        return folderMapper.selectList(new QueryWrapper<DataFillFolder>()
                .orderByAsc("sort_order")
                .orderByAsc("create_time"));
    }

    public Set<String> collectFolderAndDescendants(String folderId) {
        List<DataFillFolder> folders = listAllFolders();
        Map<String, List<String>> childMap = new HashMap<>();
        Set<String> allIds = new HashSet<>();
        for (DataFillFolder folder : folders) {
            allIds.add(folder.getId());
            childMap.computeIfAbsent(normalizeFolderId(folder.getParentId()), key -> new ArrayList<>()).add(folder.getId());
        }
        if (!allIds.contains(folderId)) {
            return Set.of();
        }

        Set<String> result = new HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add(folderId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!result.add(current)) {
                continue;
            }
            for (String childId : childMap.getOrDefault(current, List.of())) {
                queue.addLast(childId);
            }
        }
        return result;
    }

    public List<DataFillFolderNode> buildFolderTree(List<DataFillForm> visibleForms, boolean isAdmin) {
        List<DataFillFolder> folders = listAllFolders();
        Map<String, DataFillFolder> folderMap = new LinkedHashMap<>();
        for (DataFillFolder folder : folders) {
            folderMap.put(folder.getId(), folder);
        }

        Set<String> visibleFolderIds = new HashSet<>();
        Map<String, Integer> folderCounts = new HashMap<>();
        int uncategorizedCount = 0;

        for (DataFillForm form : visibleForms) {
            String folderId = normalizeFolderId(form.getFolderId());
            if (folderId == null || !folderMap.containsKey(folderId)) {
                uncategorizedCount++;
                continue;
            }

            String current = folderId;
            while (current != null && folderMap.containsKey(current)) {
                visibleFolderIds.add(current);
                folderCounts.merge(current, 1, Integer::sum);
                current = normalizeFolderId(folderMap.get(current).getParentId());
            }
        }

        if (isAdmin) {
            visibleFolderIds.addAll(folderMap.keySet());
        }

        Map<String, DataFillFolderNode> nodeMap = new LinkedHashMap<>();
        for (DataFillFolder folder : folders) {
            if (!visibleFolderIds.contains(folder.getId())) {
                continue;
            }
            DataFillFolderNode node = new DataFillFolderNode();
            node.setId(folder.getId());
            node.setName(folder.getName());
            node.setParentId(folder.getParentId());
            node.setSortOrder(folder.getSortOrder());
            node.setTemplateCount(folderCounts.getOrDefault(folder.getId(), 0));
            node.setSystemNode(false);
            nodeMap.put(folder.getId(), node);
        }

        List<DataFillFolderNode> roots = new ArrayList<>();
        for (DataFillFolder folder : folders) {
            DataFillFolderNode node = nodeMap.get(folder.getId());
            if (node == null) {
                continue;
            }
            String parentId = normalizeFolderId(folder.getParentId());
            DataFillFolderNode parent = parentId == null ? null : nodeMap.get(parentId);
            if (parent == null) {
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }

        Comparator<DataFillFolderNode> nodeComparator = Comparator
                .comparing((DataFillFolderNode node) -> node.getSortOrder() == null ? 0 : node.getSortOrder())
                .thenComparing(node -> node.getName() == null ? "" : node.getName(), String.CASE_INSENSITIVE_ORDER);
        sortNodes(roots, nodeComparator);

        if (isAdmin || uncategorizedCount > 0) {
            DataFillFolderNode uncategorized = new DataFillFolderNode();
            uncategorized.setId(UNCATEGORIZED_FOLDER_ID);
            uncategorized.setName("默认");
            uncategorized.setParentId(null);
            uncategorized.setSortOrder(Integer.MAX_VALUE);
            uncategorized.setTemplateCount(uncategorizedCount);
            uncategorized.setSystemNode(true);
            roots.add(uncategorized);
        }

        return roots;
    }

    @Transactional(rollbackFor = Exception.class)
    public DataFillFolder createFolder(DataFillFolder incoming) {
        validateFolderPayload(incoming, null);
        DataFillFolder folder = new DataFillFolder();
        folder.setName(incoming.getName().trim());
        folder.setParentId(normalizeFolderId(incoming.getParentId()));
        folder.setSortOrder(resolveSortOrder(incoming.getSortOrder(), incoming.getParentId()));
        folder.setCreator(incoming.getCreator());
        folder.setCreateTime(LocalDateTime.now());
        folder.setUpdateTime(LocalDateTime.now());
        folderMapper.insert(folder);
        return folder;
    }

    @Transactional(rollbackFor = Exception.class)
    public DataFillFolder updateFolder(String folderId, DataFillFolder incoming) {
        DataFillFolder existing = folderMapper.selectById(folderId);
        if (existing == null) {
            throw new RuntimeException("目录不存在");
        }

        validateFolderPayload(incoming, folderId);
        existing.setName(incoming.getName().trim());
        existing.setParentId(normalizeFolderId(incoming.getParentId()));
        existing.setSortOrder(resolveSortOrder(incoming.getSortOrder(), incoming.getParentId()));
        existing.setUpdateTime(LocalDateTime.now());
        folderMapper.updateById(existing);
        return existing;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteFolder(String folderId) {
        DataFillFolder existing = folderMapper.selectById(folderId);
        if (existing == null) {
            throw new RuntimeException("目录不存在");
        }

        Long childCount = folderMapper.selectCount(new QueryWrapper<DataFillFolder>().eq("parent_id", folderId));
        if (childCount != null && childCount > 0) {
            throw new RuntimeException("该目录下仍有子目录，请先删除或移动子目录");
        }

        Long formCount = formMapper.selectCount(new QueryWrapper<DataFillForm>().eq("folder_id", folderId));
        if (formCount != null && formCount > 0) {
            throw new RuntimeException("该目录下仍有关联模板，请先移动模板");
        }

        folderMapper.deleteById(folderId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void moveFormToFolder(String formId, String folderId) {
        DataFillForm form = formMapper.selectById(formId);
        if (form == null) {
            throw new RuntimeException("模板不存在");
        }

        String normalizedFolderId = normalizeFolderId(folderId);
        if (normalizedFolderId != null && folderMapper.selectById(normalizedFolderId) == null) {
            throw new RuntimeException("目标目录不存在");
        }

        form.setFolderId(normalizedFolderId);
        form.setUpdateTime(LocalDateTime.now());
        formMapper.updateById(form);
    }

    @Transactional(rollbackFor = Exception.class)
    public void moveFormsToFolder(List<String> formIds, String folderId) {
        if (formIds == null || formIds.isEmpty()) {
            throw new RuntimeException("请选择至少一个模板");
        }
        String normalizedFolderId = normalizeFolderId(folderId);
        if (normalizedFolderId != null && folderMapper.selectById(normalizedFolderId) == null) {
            throw new RuntimeException("目标目录不存在");
        }

        for (String formId : formIds) {
            if (formId == null || formId.isBlank()) {
                continue;
            }
            moveFormToFolder(formId, normalizedFolderId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void reorderFolders(List<FolderReorderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new RuntimeException("目录排序数据不能为空");
        }

        for (FolderReorderItem item : items) {
            if (item == null || item.getId() == null || item.getId().isBlank()) {
                continue;
            }
            DataFillFolder existing = folderMapper.selectById(item.getId());
            if (existing == null) {
                throw new RuntimeException("目录不存在: " + item.getId());
            }

            DataFillFolder payload = new DataFillFolder();
            payload.setName(existing.getName());
            payload.setParentId(item.getParentId());
            payload.setSortOrder(item.getSortOrder());
            validateFolderPayload(payload, existing.getId());

            existing.setParentId(normalizeFolderId(item.getParentId()));
            existing.setSortOrder(item.getSortOrder() == null ? 0 : item.getSortOrder());
            existing.setUpdateTime(LocalDateTime.now());
            folderMapper.updateById(existing);
        }
    }

    private void validateFolderPayload(DataFillFolder incoming, String currentFolderId) {
        String name = incoming == null ? null : incoming.getName();
        if (name == null || name.trim().isBlank()) {
            throw new RuntimeException("目录名称不能为空");
        }

        String parentId = incoming == null ? null : normalizeFolderId(incoming.getParentId());
        if (parentId != null) {
            DataFillFolder parent = folderMapper.selectById(parentId);
            if (parent == null) {
                throw new RuntimeException("上级目录不存在");
            }
        }

        if (currentFolderId != null && Objects.equals(currentFolderId, parentId)) {
            throw new RuntimeException("目录不能选择自己作为上级目录");
        }

        if (currentFolderId != null && parentId != null) {
            Set<String> descendants = collectFolderAndDescendants(currentFolderId);
            if (descendants.contains(parentId)) {
                throw new RuntimeException("不能将目录移动到自己的子目录下");
            }
        }

        QueryWrapper<DataFillFolder> wrapper = new QueryWrapper<DataFillFolder>()
                .eq("name", name.trim());
        if (parentId == null) {
            wrapper.isNull("parent_id");
        } else {
            wrapper.eq("parent_id", parentId);
        }
        if (currentFolderId != null) {
            wrapper.ne("id", currentFolderId);
        }

        Long duplicateCount = folderMapper.selectCount(wrapper);
        if (duplicateCount != null && duplicateCount > 0) {
            throw new RuntimeException("同级目录下已存在相同名称");
        }
    }

    private Integer resolveSortOrder(Integer incomingSortOrder, String parentId) {
        if (incomingSortOrder != null) {
            return incomingSortOrder;
        }

        QueryWrapper<DataFillFolder> wrapper = new QueryWrapper<DataFillFolder>();
        String normalizedParentId = normalizeFolderId(parentId);
        if (normalizedParentId == null) {
            wrapper.isNull("parent_id");
        } else {
            wrapper.eq("parent_id", normalizedParentId);
        }
        wrapper.orderByDesc("sort_order").last("LIMIT 1");
        DataFillFolder lastSibling = folderMapper.selectOne(wrapper);
        if (lastSibling == null || lastSibling.getSortOrder() == null) {
            return 10;
        }
        return lastSibling.getSortOrder() + 10;
    }

    private void sortNodes(List<DataFillFolderNode> nodes, Comparator<DataFillFolderNode> comparator) {
        nodes.sort(comparator);
        for (DataFillFolderNode node : nodes) {
            sortNodes(node.getChildren(), comparator);
        }
    }

    private String normalizeFolderId(String folderId) {
        if (folderId == null) {
            return null;
        }
        String trimmed = folderId.trim();
        if (trimmed.isEmpty() || UNCATEGORIZED_FOLDER_ID.equals(trimmed)) {
            return null;
        }
        return trimmed;
    }
}
