package io.choerodon.base.app.service.impl;

import static io.choerodon.base.infra.utils.SagaTopic.MemberRole.MEMBER_ROLE_DELETE;
import static io.choerodon.base.infra.utils.SagaTopic.MemberRole.MEMBER_ROLE_UPDATE;
import static io.choerodon.base.infra.utils.SagaTopic.User.ORG_USER_CREAT;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLEncoder;
import java.util.*;
import java.util.stream.Collectors;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.choerodon.asgard.saga.annotation.Saga;
import io.choerodon.asgard.saga.dto.StartInstanceDTO;
import io.choerodon.asgard.saga.feign.SagaClient;
import io.choerodon.base.api.dto.ExcelMemberRoleDTO;
import io.choerodon.base.api.dto.RoleAssignmentDeleteDTO;
import io.choerodon.base.api.dto.payload.CreateAndUpdateUserEventPayload;
import io.choerodon.base.api.dto.payload.UserMemberEventPayload;
import io.choerodon.base.api.query.ClientRoleQuery;
import io.choerodon.base.api.validator.RoleAssignmentViewValidator;
import io.choerodon.base.app.service.OrganizationUserService;
import io.choerodon.base.app.service.RoleMemberService;
import io.choerodon.base.app.service.UserService;
import io.choerodon.base.infra.asserts.UserAssertHelper;
import io.choerodon.base.infra.dto.*;
import io.choerodon.base.infra.enums.ExcelSuffix;
import io.choerodon.base.infra.enums.MemberType;
import io.choerodon.base.infra.enums.SendSettingBaseEnum;
import io.choerodon.base.infra.mapper.*;
import io.choerodon.base.infra.utils.PageUtils;
import io.choerodon.base.infra.utils.ParamUtils;
import io.choerodon.base.infra.utils.excel.ExcelImportUserTask;
import io.choerodon.core.enums.ResourceType;
import io.choerodon.core.excel.ExcelReadConfig;
import io.choerodon.core.excel.ExcelReadHelper;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.iam.ResourceLevel;
import io.choerodon.core.notify.WebHookJsonSendDTO;
import io.choerodon.core.oauth.CustomUserDetails;
import io.choerodon.core.oauth.DetailsHelper;

/**
 * @author superlee
 * @author wuguokai
 * @author zmf
 */
@Component
public class RoleMemberServiceImpl implements RoleMemberService {

    private final Logger logger = LoggerFactory.getLogger(RoleMemberServiceImpl.class);
    private static final String MEMBER_ROLE_NOT_EXIST_EXCEPTION = "error.memberRole.not.exist";
    private static final String SITE_MEMBERROLE_TEMPLATES_PATH = "/templates/siteMemberRoleTemplates";
    private static final String ORGANIZATION_MEMBERROLE_TEMPLATES_PATH = "/templates/organizationMemberRoleTemplates";
    private static final String PROJECT_MEMBERROLE_TEMPLATES_PATH = "/templates/projectMemberRoleTemplates";
    private static final String DOT_SEPARATOR = ".";
    private static final String SITE_ROOT = "role/site/default/administrator";
    private static final String ROOT_BUSINESS_TYPE_CODE = "siteAddRoot";
    private static final String USER_BUSINESS_TYPE_CODE = "siteAddUser";
    private static final String BUSINESS_TYPE_CODE = "addMember";
    private static final String PROJECT_ADD_USER = "projectAddUser";
    private ExcelImportUserTask excelImportUserTask;
    private OrganizationMapper organizationMapper;
    private ProjectMapper projectMapper;
    private ExcelImportUserTask.FinishFallback finishFallback;

    private MemberRoleMapper memberRoleMapper;

    private RoleMapper roleMapper;

    private UserAssertHelper userAssertHelper;

    @Value("${choerodon.devops.message:false}")
    private boolean devopsMessage;

    private final ObjectMapper mapper = new ObjectMapper();

    private SagaClient sagaClient;

    private LabelMapper labelMapper;

    private ClientMapper clientMapper;

    private UploadHistoryMapper uploadHistoryMapper;

    private OrganizationUserService organizationUserService;

    private UserService userService;

    private UserMapper userMapper;


    public RoleMemberServiceImpl(ExcelImportUserTask excelImportUserTask,
                                 ExcelImportUserTask.FinishFallback finishFallback,
                                 OrganizationMapper organizationMapper,
                                 ProjectMapper projectMapper,
                                 MemberRoleMapper memberRoleMapper,
                                 RoleMapper roleMapper,
                                 UserAssertHelper userAssertHelper,
                                 SagaClient sagaClient,
                                 LabelMapper labelMapper,
                                 ClientMapper clientMapper,
                                 UploadHistoryMapper uploadHistoryMapper,
                                 OrganizationUserService organizationUserService,
                                 UserService userService,
                                 UserMapper userMapper) {
        this.excelImportUserTask = excelImportUserTask;
        this.finishFallback = finishFallback;
        this.organizationMapper = organizationMapper;
        this.projectMapper = projectMapper;
        this.memberRoleMapper = memberRoleMapper;
        this.roleMapper = roleMapper;
        this.userAssertHelper = userAssertHelper;
        this.sagaClient = sagaClient;
        this.labelMapper = labelMapper;
        this.clientMapper = clientMapper;
        this.uploadHistoryMapper = uploadHistoryMapper;
        this.organizationUserService = organizationUserService;
        this.userService = userService;
        this.userMapper = userMapper;
    }


    @Transactional(rollbackFor = CommonException.class)
    @Override
    public List<MemberRoleDTO> createOrUpdateRolesByMemberIdOnSiteLevel(Boolean isEdit, List<Long> memberIds, List<MemberRoleDTO> memberRoleDTOList, String memberType) {
        List<MemberRoleDTO> memberRoleDTOS = new ArrayList<>();
        memberType = validate(memberRoleDTOList, memberType);
        // member type 为 'client' 时
        if (memberType != null && memberType.equals(MemberType.CLIENT.value())) {
            for (Long memberId : memberIds) {
                memberRoleDTOList.forEach(m ->
                        m.setMemberId(memberId)
                );
                memberRoleDTOS.addAll(
                        insertOrUpdateRolesOfClientByMemberId(isEdit, 0L, memberId,
                                memberRoleDTOList,
                                ResourceLevel.SITE.value()));
            }
            return memberRoleDTOS;
        }

        // member type 为 'user' 时
        for (Long memberId : memberIds) {
            memberRoleDTOList.forEach(m ->
                    m.setMemberId(memberId)
            );
            memberRoleDTOS.addAll(
                    insertOrUpdateRolesOfUserByMemberId(isEdit, 0L, memberId, memberRoleDTOList, ResourceLevel.SITE.value()));
        }
        return memberRoleDTOS;
    }

    @Transactional(rollbackFor = CommonException.class)
    @Override
    public List<MemberRoleDTO> createOrUpdateRolesByMemberIdOnOrganizationLevel(Boolean isEdit, Long organizationId, List<Long> memberIds, List<MemberRoleDTO> memberRoleDTOList, String memberType) {
        List<MemberRoleDTO> memberRoleDTOS = new ArrayList<>();

        memberType = validate(memberRoleDTOList, memberType);

        // member type 为 'client' 时
        if (memberType != null && memberType.equals(MemberType.CLIENT.value())) {
            for (Long memberId : memberIds) {
                memberRoleDTOList.forEach(m ->
                        m.setMemberId(memberId)
                );
                memberRoleDTOS.addAll(
                        insertOrUpdateRolesOfClientByMemberId(isEdit, organizationId, memberId,
                                memberRoleDTOList,
                                ResourceLevel.ORGANIZATION.value()));
            }
            return memberRoleDTOS;
        }

        // member type 为 'user' 时
        for (Long memberId : memberIds) {
            memberRoleDTOList.forEach(m ->
                    m.setMemberId(memberId)
            );
            memberRoleDTOS.addAll(
                    insertOrUpdateRolesOfUserByMemberId(isEdit, organizationId, memberId,
                            memberRoleDTOList,
                            ResourceLevel.ORGANIZATION.value()));
        }
        return memberRoleDTOS;
    }

    private String validate(List<MemberRoleDTO> memberRoleDTOList, String memberType) {
        if (memberType == null && memberRoleDTOList != null && !memberRoleDTOList.isEmpty()) {
            memberType = memberRoleDTOList.get(0).getMemberType();
        }
        if (memberRoleDTOList == null) {
            throw new CommonException("error.memberRole.null");
        }
        return memberType;
    }

    @Override
    public PageInfo<ClientDTO> pagingQueryClientsWithRoles(Pageable pageable, ClientRoleQuery clientRoleSearchDTO,
                                                           Long sourceId, ResourceType resourceType) {
        int page = pageable.getPageNumber();
        int size = pageable.getPageSize();
        int start = PageUtils.getBegin(page, size);
        String param = ParamUtils.arrToStr(clientRoleSearchDTO.getParam());
        try (Page<ClientDTO> result = new Page<>(page, size)) {
            int count = memberRoleMapper.selectCountClients(sourceId, resourceType.value(), clientRoleSearchDTO, param);
            result.setTotal(count);
            result.addAll(memberRoleMapper.selectClientsWithRoles(sourceId, resourceType.value(), clientRoleSearchDTO, param, start, size));
            return result.toPageInfo();
        }
    }


    @Transactional(rollbackFor = CommonException.class)
    @Override
    public List<MemberRoleDTO> createOrUpdateRolesByMemberIdOnProjectLevel(Boolean isEdit, Long projectId, List<Long> memberIds, List<MemberRoleDTO> memberRoleDTOList, String memberType) {
        List<MemberRoleDTO> memberRoleDTOS = new ArrayList<>();

        memberType = validate(memberRoleDTOList, memberType);

        // member type 为 'client' 时
        if (memberType != null && memberType.equals(MemberType.CLIENT.value())) {
            for (Long memberId : memberIds) {
                memberRoleDTOList.forEach(m ->
                        m.setMemberId(memberId)
                );
                memberRoleDTOS.addAll(
                        insertOrUpdateRolesOfClientByMemberId(isEdit, projectId, memberId,
                                memberRoleDTOList,
                                ResourceLevel.PROJECT.value()));
            }
            return memberRoleDTOS;
        }

        // member type 为 'user' 时
        for (Long memberId : memberIds) {
            memberRoleDTOList.forEach(m ->
                    m.setMemberId(memberId)
            );
            memberRoleDTOS.addAll(
                    insertOrUpdateRolesOfUserByMemberId(isEdit, projectId, memberId,
                            memberRoleDTOList,
                            ResourceLevel.PROJECT.value()));
        }
        return memberRoleDTOS;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOnSiteLevel(RoleAssignmentDeleteDTO roleAssignmentDeleteDTO) {
        String memberType = roleAssignmentDeleteDTO.getMemberType();
        if (memberType != null && memberType.equals(MemberType.CLIENT.value())) {
            deleteClientAndRole(roleAssignmentDeleteDTO, ResourceLevel.SITE.value());
            return;
        }
        delete(roleAssignmentDeleteDTO, ResourceLevel.SITE.value());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOnOrganizationLevel(RoleAssignmentDeleteDTO roleAssignmentDeleteDTO) {
        String memberType = roleAssignmentDeleteDTO.getMemberType();
        if (memberType != null && memberType.equals(MemberType.CLIENT.value())) {
            deleteClientAndRole(roleAssignmentDeleteDTO, ResourceLevel.ORGANIZATION.value());
            return;
        }
        delete(roleAssignmentDeleteDTO, ResourceLevel.ORGANIZATION.value());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOnProjectLevel(RoleAssignmentDeleteDTO roleAssignmentDeleteDTO, Boolean syncAll) {
        String memberType = roleAssignmentDeleteDTO.getMemberType();
        if (memberType != null && memberType.equals(MemberType.CLIENT.value())) {
            deleteClientAndRole(roleAssignmentDeleteDTO, ResourceLevel.PROJECT.value());
            return;
        }
        delete(roleAssignmentDeleteDTO, ResourceLevel.PROJECT.value(),syncAll);
        //删除用户所有项目角色时发送web hook
        JSONObject jsonObject = new JSONObject();
        List<Long> collect = roleAssignmentDeleteDTO.getData().keySet().stream().collect(Collectors.toList());
        jsonObject.put("projectId", roleAssignmentDeleteDTO.getSourceId());
        jsonObject.put("user", JSON.toJSONString(userService.getWebHookUser(collect.get(0))));
        UserDTO userDTO = userMapper.selectByPrimaryKey(collect.get(0));

        WebHookJsonSendDTO webHookJsonSendDTO = new WebHookJsonSendDTO(
                SendSettingBaseEnum.DELETE_USERROLES.value(),
                SendSettingBaseEnum.map.get(SendSettingBaseEnum.DELETE_USERROLES.value()),
                jsonObject,
                userDTO.getLastUpdateDate(),
                userService.getWebHookUser(DetailsHelper.getUserDetails().getUserId())
        );
        Map<String, Object> params = new HashMap<>();
        userService.sendNotice(DetailsHelper.getUserDetails().getUserId(), Arrays.asList(userDTO.getId()), SendSettingBaseEnum.DELETE_USERROLES.value(), params, roleAssignmentDeleteDTO.getSourceId(), webHookJsonSendDTO);
    }

    @Override
    public void deleteOnProjectLevel(RoleAssignmentDeleteDTO roleAssignmentDeleteDTO) {
        deleteOnProjectLevel(roleAssignmentDeleteDTO, false);
    }

    @Override
    public ResponseEntity<Resource> downloadTemplatesByResourceLevel(String suffix, String resourceLevel) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.add("Pragma", "no-cache");
        headers.add("Expires", "0");
        headers.add("charset", "utf-8");
        //设置下载文件名
        String filename = "用户角色关系导入模板." + suffix;
        try {
            filename = URLEncoder.encode(filename, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            logger.info("url encodes exception: {}", e.getMessage());
            throw new CommonException("error.encode.url");
        }
        headers.add("Content-Disposition", "attachment;filename=\"" + filename + "\"");
        InputStream inputStream;
        // 根据层级，设置excel文件路径
        String excelPath;
        if (ResourceLevel.SITE.value().equals(resourceLevel)) {
            excelPath = SITE_MEMBERROLE_TEMPLATES_PATH + DOT_SEPARATOR + suffix;
        } else if (ResourceLevel.ORGANIZATION.value().equals(resourceLevel)) {
            excelPath = ORGANIZATION_MEMBERROLE_TEMPLATES_PATH + DOT_SEPARATOR + suffix;
        } else if (ResourceLevel.PROJECT.value().equals(resourceLevel)) {
            excelPath = PROJECT_MEMBERROLE_TEMPLATES_PATH + DOT_SEPARATOR + suffix;
        } else {
            return null;
        }
        // 根据excel类型，设置响应头mediaType
        String mediaTypeValue;
        if (ExcelSuffix.XLS.value().equals(suffix)) {
            mediaTypeValue = "application/vnd.ms-excel";
        } else if (ExcelSuffix.XLSX.value().equals(suffix)) {
            mediaTypeValue = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        } else {
            return null;
        }

        inputStream = this.getClass().getResourceAsStream(excelPath);
        return ResponseEntity
                .ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(mediaTypeValue))
                .body(new InputStreamResource(inputStream));

    }


    @Override
    public void import2MemberRole(Long sourceId, String sourceType, MultipartFile file) {
        CustomUserDetails userDetails = DetailsHelper.getUserDetails();
        validateSourceId(sourceId, sourceType);
        ExcelReadConfig excelReadConfig = initExcelReadConfig();
        long begin = System.currentTimeMillis();
        try {
            List<ExcelMemberRoleDTO> memberRoles = ExcelReadHelper.read(file, ExcelMemberRoleDTO.class, excelReadConfig);
            if (memberRoles.isEmpty()) {
                throw new CommonException("error.excel.memberRole.empty");
            }
            UploadHistoryDTO uploadHistory = initUploadHistory(sourceId, sourceType);
            long end = System.currentTimeMillis();
            logger.info("read excel for {} millisecond", (end - begin));
            excelImportUserTask.importMemberRole(userDetails.getUserId(), memberRoles, uploadHistory, finishFallback);
        } catch (IOException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
            throw new CommonException("error.excel.read", e);
        } catch (IllegalArgumentException e) {
            throw new CommonException("error.excel.illegal.column", e);
        }
    }


    @Override
    public MemberRoleDTO insertSelective(MemberRoleDTO memberRoleDTO) {
        if (memberRoleDTO.getMemberType() == null) {
            memberRoleDTO.setMemberType("user");
        }
        RoleDTO roleDTO = roleMapper.selectByPrimaryKey(memberRoleDTO.getRoleId());
        if (roleDTO == null) {
            throw new CommonException("error.member_role.insert.role.not.exist");
        }
        if (ResourceLevel.PROJECT.value().equals(memberRoleDTO.getSourceType())
                && projectMapper.selectByPrimaryKey(memberRoleDTO.getSourceId()) == null) {
            throw new CommonException("error.member_role.insert.project.not.exist");
        }
        if (ResourceLevel.ORGANIZATION.value().equals(memberRoleDTO.getSourceType())
                && organizationMapper.selectByPrimaryKey(memberRoleDTO.getSourceId()) == null) {
            throw new CommonException("error.member_role.insert.organization.not.exist");
        }
        if (memberRoleMapper.selectOne(memberRoleDTO) != null) {
            throw new CommonException("error.member_role.has.existed");
        }
        if (memberRoleMapper.insertSelective(memberRoleDTO) != 1) {
            throw new CommonException("error.member_role.create");
        }
        //如果是平台root更新user表
        if (SITE_ROOT.equals(roleDTO.getCode())) {
            UserDTO userDTO = userMapper.selectByPrimaryKey(memberRoleDTO.getMemberId());
            userDTO.setAdmin(true);
            userMapper.updateByPrimaryKey(userDTO);
        }
        return memberRoleMapper.selectByPrimaryKey(memberRoleDTO.getId());
    }

    private UploadHistoryDTO initUploadHistory(Long sourceId, String sourceType) {
        UploadHistoryDTO uploadHistory = new UploadHistoryDTO();
        uploadHistory.setBeginTime(new Date(System.currentTimeMillis()));
        uploadHistory.setType("member-role");
        uploadHistory.setUserId(DetailsHelper.getUserDetails().getUserId());
        uploadHistory.setSourceId(sourceId);
        uploadHistory.setSourceType(sourceType);
        if (uploadHistoryMapper.insertSelective(uploadHistory) != 1) {
            throw new CommonException("error.uploadHistory.insert");
        }
        return uploadHistoryMapper.selectByPrimaryKey(uploadHistory);
    }

    private void validateSourceId(Long sourceId, String sourceType) {
        if (ResourceLevel.ORGANIZATION.value().equals(sourceType)
                && organizationMapper.selectByPrimaryKey(sourceId) == null) {
            throw new CommonException("error.organization.not.exist");
        }
        if (ResourceLevel.PROJECT.value().equals(sourceType)
                && projectMapper.selectByPrimaryKey(sourceId) == null) {
            throw new CommonException("error.project.not.exist", sourceId);
        }
    }

    private ExcelReadConfig initExcelReadConfig() {
        ExcelReadConfig excelReadConfig = new ExcelReadConfig();
        String[] skipSheetNames = {"readme"};
        Map<String, String> propertyMap = new HashMap<>();
        propertyMap.put("登录名*", "loginName");
        propertyMap.put("角色编码*", "roleCode");
        excelReadConfig.setSkipSheetNames(skipSheetNames);
        excelReadConfig.setPropertyMap(propertyMap);
        return excelReadConfig;
    }

    @Override
    public List<MemberRoleDTO> insertOrUpdateRolesOfUserByMemberId(Boolean isEdit, Long sourceId, Long memberId, List<MemberRoleDTO> memberRoles, String sourceType) {
        return insertOrUpdateRolesOfUserByMemberId(isEdit,sourceId,memberId,memberRoles,sourceType,false);
    }

    @Override
    @Saga(code = MEMBER_ROLE_UPDATE, description = "iam更新用户角色", inputSchemaClass = List.class)
    @Transactional(rollbackFor = Exception.class)
    public List<MemberRoleDTO> insertOrUpdateRolesOfUserByMemberId(Boolean isEdit, Long sourceId, Long memberId, List<MemberRoleDTO> memberRoles, String sourceType, Boolean syncAll) {
        Long userId = DetailsHelper.getUserDetails().getUserId();
        UserDTO userDTO = userAssertHelper.userNotExisted(memberId);
        List<MemberRoleDTO> returnList = new ArrayList<>();
        if (devopsMessage) {
            List<UserMemberEventPayload> userMemberEventPayloads = new ArrayList<>();
            UserMemberEventPayload userMemberEventMsg = new UserMemberEventPayload();
            userMemberEventMsg.setResourceId(sourceId);
            userMemberEventMsg.setUserId(memberId);
            userMemberEventMsg.setResourceType(sourceType);
            userMemberEventMsg.setUsername(userDTO.getLoginName());
            userMemberEventMsg.setSyncAll(syncAll);

            List<Long> ownRoleIds = insertOrUpdateRolesByMemberIdExecute(userId,
                    isEdit, sourceId, memberId, sourceType, memberRoles, returnList, MemberType.USER.value());
            if (!ownRoleIds.isEmpty()) {
                userMemberEventMsg.setRoleLabels(labelMapper.selectLabelNamesInRoleIds(ownRoleIds));
            }
            userMemberEventPayloads.add(userMemberEventMsg);
            sendEvent(userMemberEventPayloads, MEMBER_ROLE_UPDATE);
            return returnList;
        } else {
            insertOrUpdateRolesByMemberIdExecute(userId, isEdit,
                    sourceId,
                    memberId,
                    sourceType,
                    memberRoles,
                    returnList, MemberType.USER.value());
            return returnList;
        }
    }

    public List<Long> insertOrUpdateRolesByMemberIdExecute(Long fromUserId, Boolean isEdit, Long sourceId,
                                                           Long memberId, String sourceType,
                                                           List<MemberRoleDTO> memberRoleList,
                                                           List<MemberRoleDTO> returnList, String memberType) {
        MemberRoleDTO memberRole = new MemberRoleDTO();
        memberRole.setMemberId(memberId);
        memberRole.setMemberType(memberType);
        memberRole.setSourceId(sourceId);
        memberRole.setSourceType(sourceType);
        List<MemberRoleDTO> existingMemberRoleList = memberRoleMapper.select(memberRole);
        List<Long> existingRoleIds =
                existingMemberRoleList.stream().map(MemberRoleDTO::getRoleId).collect(Collectors.toList());
        List<Long> newRoleIds = memberRoleList.stream().map(MemberRoleDTO::getRoleId).collect(Collectors.toList());
        //交集，传入的roleId与数据库里存在的roleId相交
        List<Long> intersection = existingRoleIds.stream().filter(newRoleIds::contains).collect(Collectors.toList());
        //传入的roleId与交集的差集为要插入的roleId
        List<Long> insertList = newRoleIds.stream().filter(item ->
                !intersection.contains(item)).collect(Collectors.toList());
        //数据库存在的roleId与交集的差集为要删除的roleId
        List<Long> deleteList = existingRoleIds.stream().filter(item ->
                !intersection.contains(item)).collect(Collectors.toList());
        returnList.addAll(existingMemberRoleList);
        List<MemberRoleDTO> memberRoleDTOS = new ArrayList<>();
        insertList.forEach(roleId -> {
            MemberRoleDTO mr = new MemberRoleDTO();
            mr.setRoleId(roleId);
            mr.setMemberId(memberId);
            mr.setMemberType(memberType);
            mr.setSourceType(sourceType);
            mr.setSourceId(sourceId);
            MemberRoleDTO memberRoleDTO = insertSelective(mr);
            returnList.add(memberRoleDTO);
            memberRoleDTOS.add(memberRoleDTO);
        });
        //批量添加，导入成功发送消息
        memberRoleDTOS.stream().forEach(memberRoleDTO -> {
            snedMsg(sourceType, fromUserId, memberRoleDTO, sourceId, memberRoleDTOS);
        });

        if (isEdit != null && isEdit && !deleteList.isEmpty()) {
            memberRoleMapper.selectDeleteList(memberId, sourceId, memberType, sourceType, deleteList)
                    .forEach(t -> {
                        if (t != null) {
                            memberRoleMapper.deleteByPrimaryKey(t);
                            returnList.removeIf(memberRoleDTO -> memberRoleDTO.getId().equals(t));
                        }
                    });
        }
        //查当前用户/客户端有那些角色
        return memberRoleMapper.select(memberRole)
                .stream().map(MemberRoleDTO::getRoleId).collect(Collectors.toList());
    }

    private void snedMsg(String sourceType, Long fromUserId, MemberRoleDTO memberRoleDTO, Long sourceId, List<MemberRoleDTO> memberRoleDTOS) {
        CustomUserDetails userDetails = DetailsHelper.getUserDetails();
        RoleDTO roleDTO = roleMapper.selectByPrimaryKey(memberRoleDTO.getRoleId());
        Map<String, Object> params = new HashMap<>();
        if (ResourceType.SITE.value().equals(sourceType)) {
            if (SITE_ROOT.equals(roleDTO.getCode())) {
                userService.sendNotice(fromUserId, Arrays.asList(memberRoleDTO.getMemberId()), ROOT_BUSINESS_TYPE_CODE, Collections.EMPTY_MAP, sourceId);
            } else {
                params.put("roleName", roleDTO.getName());
                userService.sendNotice(fromUserId, Arrays.asList(memberRoleDTO.getMemberId()), USER_BUSINESS_TYPE_CODE, params, sourceId);
            }
        }
        if (ResourceType.ORGANIZATION.value().equals(sourceType)) {
            OrganizationDTO organizationDTO = organizationMapper.selectByPrimaryKey(sourceId);
            params.put("organizationName", organizationDTO.getName());
            params.put("roleName", roleDTO.getName());
            //webhook json
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("organizationId", organizationDTO.getId());
            jsonObject.put("addCount", 1);
            WebHookJsonSendDTO.User webHookUser = userService.getWebHookUser(memberRoleDTO.getMemberId());
            jsonObject.put("userList", JSON.toJSONString(Arrays.asList(webHookUser)));

            WebHookJsonSendDTO webHookJsonSendDTO = new WebHookJsonSendDTO(
                    SendSettingBaseEnum.ADD_MEMBER.value(),
                    SendSettingBaseEnum.map.get(SendSettingBaseEnum.ADD_MEMBER.value()),
                    jsonObject,
                    new Date(),
                    userService.getWebHookUser(fromUserId)
            );
            userService.sendNotice(fromUserId, Arrays.asList(memberRoleDTO.getMemberId()), BUSINESS_TYPE_CODE, params, sourceId, webHookJsonSendDTO);
        }
        if (ResourceType.PROJECT.value().equals(sourceType)) {
            ProjectDTO projectDTO = projectMapper.selectByPrimaryKey(sourceId);
            params.put("projectName", projectDTO);
            params.put("roleName", roleDTO.getName());

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("organizationId", projectDTO.getOrganizationId());
            jsonObject.put("addCount", 1);
            WebHookJsonSendDTO.User webHookUser = userService.getWebHookUser(memberRoleDTO.getMemberId());
            jsonObject.put("userList", JSON.toJSONString(Arrays.asList(webHookUser)));

            WebHookJsonSendDTO webHookJsonSendDTO = new WebHookJsonSendDTO(
                    SendSettingBaseEnum.PROJECT_ADDUSER.value(),
                    SendSettingBaseEnum.map.get(SendSettingBaseEnum.PROJECT_ADDUSER.value()),
                    jsonObject,
                    new Date(),
                    userService.getWebHookUser(fromUserId)
            );
            userService.sendNotice(fromUserId, Arrays.asList(memberRoleDTO.getMemberId()), PROJECT_ADD_USER, params, sourceId, webHookJsonSendDTO);
        }
    }

    private void sendEvent(List<UserMemberEventPayload> userMemberEventPayloads, String code) {
        try {
            String input = mapper.writeValueAsString(userMemberEventPayloads);
            String refIds = userMemberEventPayloads.stream().map(t -> t.getUserId() + "").collect(Collectors.joining(","));
            String level = userMemberEventPayloads.get(0).getResourceType();
            Long sourceId = userMemberEventPayloads.get(0).getResourceId();
            sagaClient.startSaga(code, new StartInstanceDTO(input, "users", refIds, level, sourceId));
        } catch (Exception e) {
            throw new CommonException("error.iRoleMemberServiceImpl.updateMemberRole.event", e);
        }
    }

    @Override
    public List<MemberRoleDTO> insertOrUpdateRolesOfClientByMemberId(Boolean isEdit, Long sourceId, Long memberId, List<MemberRoleDTO> memberRoles, String sourceType) {
        Long userId = DetailsHelper.getUserDetails().getUserId();
        ClientDTO client = clientMapper.selectByPrimaryKey(memberId);
        if (client == null) {
            throw new CommonException("error.client.not.exist");
        }
        List<MemberRoleDTO> returnList = new ArrayList<>();
        insertOrUpdateRolesByMemberIdExecute(userId, isEdit,
                sourceId,
                memberId,
                sourceType,
                memberRoles,
                returnList, MemberType.CLIENT.value());
        return returnList;
    }

    @Override
    public void deleteClientAndRole(RoleAssignmentDeleteDTO roleAssignmentDeleteDTO, String sourceType) {
        deleteByView(roleAssignmentDeleteDTO, sourceType, null,false);
    }

    private void deleteByView(RoleAssignmentDeleteDTO roleAssignmentDeleteDTO,
                              String sourceType,
                              List<UserMemberEventPayload> userMemberEventPayloads,
                              Boolean syncAll) {
        boolean doSendEvent = userMemberEventPayloads != null;
        // 默认的 member type 是 'user'
        String memberType =
                roleAssignmentDeleteDTO.getMemberType() == null ? MemberType.USER.value() : roleAssignmentDeleteDTO.getMemberType();
        String view = roleAssignmentDeleteDTO.getView();
        Long sourceId = roleAssignmentDeleteDTO.getSourceId();
        Map<Long, List<Long>> data = roleAssignmentDeleteDTO.getData();
        if (RoleAssignmentViewValidator.USER_VIEW.equalsIgnoreCase(view)) {
            deleteFromMap(data, false, memberType, sourceId, sourceType, doSendEvent, userMemberEventPayloads, syncAll);
        } else if (RoleAssignmentViewValidator.ROLE_VIEW.equalsIgnoreCase(view)) {
            deleteFromMap(data, true, memberType, sourceId, sourceType, doSendEvent, userMemberEventPayloads, syncAll);
        }
    }

    /**
     * 根据数据批量删除 member-role 记录
     *
     * @param data   数据
     * @param isRole data的键是否是 roleId
     */
    private void deleteFromMap(Map<Long, List<Long>> data, boolean isRole, String memberType, Long sourceId, String sourceType, boolean doSendEvent, List<UserMemberEventPayload> userMemberEventPayloads, Boolean syncAll) {
        for (Map.Entry<Long, List<Long>> entry : data.entrySet()) {
            Long key = entry.getKey();
            List<Long> values = entry.getValue();
            if (values != null && !values.isEmpty()) {
                values.forEach(id -> {
                    Long roleId;
                    Long memberId;
                    if (isRole) {
                        roleId = key;
                        memberId = id;
                    } else {
                        roleId = id;
                        memberId = key;
                    }
                    UserMemberEventPayload userMemberEventPayload =
                            delete(roleId, memberId, memberType, sourceId, sourceType, doSendEvent);
                    if (userMemberEventPayload != null) {
                        userMemberEventPayload.setSyncAll(syncAll);
                        userMemberEventPayloads.add(userMemberEventPayload);
                    }
                });
            }
        }
    }

    private UserMemberEventPayload delete(Long roleId, Long memberId, String memberType,
                                          Long sourceId, String sourceType, boolean doSendEvent) {
        MemberRoleDTO memberRole = new MemberRoleDTO();
        memberRole.setRoleId(roleId);
        memberRole.setMemberId(memberId);
        memberRole.setMemberType(memberType);
        memberRole.setSourceId(sourceId);
        memberRole.setSourceType(sourceType);
        MemberRoleDTO mr = memberRoleMapper.selectOne(memberRole);
        if (mr == null) {
            throw new CommonException(MEMBER_ROLE_NOT_EXIST_EXCEPTION, roleId, memberId);
        }
        memberRoleMapper.deleteByPrimaryKey(mr.getId());
        UserMemberEventPayload userMemberEventMsg = null;
        //查询移除的role所包含的所有Label
        if (doSendEvent) {
            userMemberEventMsg = new UserMemberEventPayload();
            userMemberEventMsg.setResourceId(sourceId);
            userMemberEventMsg.setResourceType(sourceType);
            UserDTO user = userAssertHelper.userNotExisted(memberId);
            userMemberEventMsg.setUsername(user.getLoginName());
            userMemberEventMsg.setUserId(memberId);
        }
        return userMemberEventMsg;
    }

    @Override
    public void delete(RoleAssignmentDeleteDTO roleAssignmentDeleteDTO, String sourceType) {
        delete( roleAssignmentDeleteDTO,  sourceType,null);
    }

    @Saga(code = MEMBER_ROLE_DELETE, description = "iam删除用户角色")
    public void delete(RoleAssignmentDeleteDTO roleAssignmentDeleteDTO, String sourceType, Boolean syncAll) {
        if (devopsMessage) {
            List<UserMemberEventPayload> userMemberEventPayloads = new ArrayList<>();
            deleteByView(roleAssignmentDeleteDTO, sourceType, userMemberEventPayloads, syncAll);
            try {
                String input = mapper.writeValueAsString(userMemberEventPayloads);
                String refIds = userMemberEventPayloads.stream().map(t -> t.getUserId() + "").collect(Collectors.joining(","));
                sagaClient.startSaga(MEMBER_ROLE_DELETE, new StartInstanceDTO(input, "users", refIds, sourceType, roleAssignmentDeleteDTO.getSourceId()));
            } catch (Exception e) {
                throw new CommonException("error.iRoleMemberServiceImpl.deleteMemberRole.event", e);
            }
        } else {
            deleteByView(roleAssignmentDeleteDTO, sourceType, null, syncAll);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Saga(code = ORG_USER_CREAT, description = "组织层创建用户", inputSchemaClass = CreateAndUpdateUserEventPayload.class)
    public void insertAndSendEvent(Long fromUserId, UserDTO userDTO, MemberRoleDTO memberRole, String loginName) {
        RoleDTO roleDTO = roleMapper.selectByPrimaryKey(memberRole.getRoleId());
        if (devopsMessage) {
            organizationUserService.createUserAndUpdateRole(fromUserId, userDTO, Arrays.asList(roleDTO), memberRole.getSourceType(), memberRole.getSourceId());
        }
    }

    @Override
    @Saga(code = MEMBER_ROLE_DELETE, description = "iam删除用户角色")
    @Transactional(rollbackFor = Exception.class)
    public void deleteOrgAdmin(Long organizationId, Long userId, List<MemberRoleDTO> memberRoleDTOS, String value, Set<String> lableNames) {
        UserDTO userDTO = userAssertHelper.userNotExisted(userId);
        if (devopsMessage) {
            List<UserMemberEventPayload> userMemberEventPayloads = new ArrayList<>();
            UserMemberEventPayload userMemberEventMsg = new UserMemberEventPayload();
            userMemberEventMsg.setResourceId(organizationId);
            userMemberEventMsg.setUserId(userDTO.getId());
            userMemberEventMsg.setResourceType(ResourceType.ORGANIZATION.value());
            userMemberEventMsg.setUsername(userDTO.getLoginName());
            userMemberEventMsg.setRoleLabels(lableNames);
            userMemberEventPayloads.add(userMemberEventMsg);
            sendEvent(userMemberEventPayloads, MEMBER_ROLE_DELETE);
        }
    }
}
