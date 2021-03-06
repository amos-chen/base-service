package io.choerodon.base.api.controller.v1;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ObjectUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import io.choerodon.core.annotation.Permission;
import io.choerodon.base.api.dto.ResetPasswordDTO;
import io.choerodon.base.api.validator.SysSettingValidator;
import io.choerodon.base.api.vo.SysSettingVO;
import io.choerodon.base.app.service.SystemSettingService;
import io.choerodon.core.enums.ResourceType;
import io.choerodon.core.base.BaseController;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.iam.InitRoleCode;

/**
 * @author zmf
 * @since 2018-10-15
 */
@RestController
@RequestMapping(value = "/v1/system/setting")
public class SystemSettingController extends BaseController {
    private final SystemSettingService systemSettingService;

    public SystemSettingController(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    @PostMapping
    @ApiOperation(value = "添加/更新平台基本信息")
    @Permission(type = ResourceType.SITE, roles = InitRoleCode.SITE_ADMINISTRATOR)
    public ResponseEntity<SysSettingVO> updateGeneralInfo(@RequestBody @Validated({SysSettingValidator.GeneralInfoGroup.class})
                                                                  SysSettingVO sysSettingVO, BindingResult result) {
        if (result.hasErrors()) {
            throw new CommonException(result.getAllErrors().get(0).getDefaultMessage());
        }
        return new ResponseEntity<>(systemSettingService.updateGeneralInfo(sysSettingVO), HttpStatus.OK);
    }

    @PostMapping(value = "/passwordPolicy")
    @ApiOperation(value = "添加/更新平台密码策略")
    @Permission(type = ResourceType.SITE, roles = InitRoleCode.SITE_ADMINISTRATOR)
    public ResponseEntity<SysSettingVO> updatePasswordPolicy(@RequestBody @Validated({SysSettingValidator.PasswordPolicyGroup.class})
                                                                     SysSettingVO sysSettingVO, BindingResult result) {
        if (result.hasErrors()) {
            throw new CommonException(result.getAllErrors().get(0).getDefaultMessage());
        }
        return new ResponseEntity<>(systemSettingService.updatePasswordPolicy(sysSettingVO), HttpStatus.OK);
    }

    @DeleteMapping
    @ApiOperation(value = "重置平台基本信息")
    @Permission(type = ResourceType.SITE, roles = InitRoleCode.SITE_ADMINISTRATOR)
    public ResponseEntity resetGeneralInfo() {
        systemSettingService.resetGeneralInfo();
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @GetMapping
    @ApiOperation(value = "获取平台基本信息、密码策略及Feedback策略")
    @Permission(type = ResourceType.SITE, permissionPublic = true)
    public ResponseEntity<Object> getSetting() {
        SysSettingVO sysSettingVO = systemSettingService.getSetting();
        Object result;
        result = sysSettingVO == null ? "{}" : sysSettingVO;
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @PostMapping(value = "/upload/favicon")
    @ApiOperation(value = "上传平台徽标")
    @Permission(type = ResourceType.SITE, roles = InitRoleCode.SITE_ADMINISTRATOR)
    public ResponseEntity<String> uploadFavicon(@RequestPart MultipartFile file,
                                                @ApiParam(name = "rotate", value = "顺时针旋转的角度", example = "90")
                                                @RequestParam(required = false) Double rotate,
                                                @ApiParam(name = "startX", value = "裁剪的X轴", example = "100")
                                                @RequestParam(required = false, name = "startX") Integer axisX,
                                                @ApiParam(name = "startY", value = "裁剪的Y轴", example = "100")
                                                @RequestParam(required = false, name = "startY") Integer axisY,
                                                @ApiParam(name = "endX", value = "裁剪的宽度", example = "200")
                                                @RequestParam(required = false, name = "endX") Integer width,
                                                @ApiParam(name = "endY", value = "裁剪的高度", example = "200")
                                                @RequestParam(required = false, name = "endY") Integer height) {
        return new ResponseEntity<>(systemSettingService.uploadFavicon(file, rotate, axisX, axisY, width, height), HttpStatus.OK);
    }

    @PostMapping(value = "/upload/logo")
    @ApiOperation(value = "上传平台logo")
    @Permission(type = ResourceType.SITE, roles = InitRoleCode.SITE_ADMINISTRATOR)
    public ResponseEntity<String> uploadLogo(@RequestPart MultipartFile file,
                                             @ApiParam(name = "rotate", value = "顺时针旋转的角度", example = "90")
                                             @RequestParam(required = false) Double rotate,
                                             @ApiParam(name = "startX", value = "裁剪的X轴", example = "100")
                                             @RequestParam(required = false, name = "startX") Integer axisX,
                                             @ApiParam(name = "startY", value = "裁剪的Y轴", example = "100")
                                             @RequestParam(required = false, name = "startY") Integer axisY,
                                             @ApiParam(name = "endX", value = "裁剪的宽度", example = "200")
                                             @RequestParam(required = false, name = "endX") Integer width,
                                             @ApiParam(name = "endY", value = "裁剪的高度", example = "200")
                                             @RequestParam(required = false, name = "endY") Integer height) {
        return new ResponseEntity<>(systemSettingService.uploadSystemLogo(file, rotate, axisX, axisY, width, height), HttpStatus.OK);
    }

    @GetMapping(value = "/enable_resetPassword")
    @ApiOperation(value = "是否允许修改仓库密码")
    @Permission(type = ResourceType.SITE, permissionLogin = true)
    public ResponseEntity<ResetPasswordDTO> enableResetPassword() {
        SysSettingVO sysSettingVO = systemSettingService.getSetting();
        boolean result = !ObjectUtils.isEmpty(sysSettingVO.getResetGitlabPasswordUrl());
        ResetPasswordDTO resetPasswordDTO = new ResetPasswordDTO();
        if (result) {
            resetPasswordDTO.setEnable_reset(true);
            resetPasswordDTO.setResetGitlabPasswordUrl(sysSettingVO.getResetGitlabPasswordUrl());
        } else {
            resetPasswordDTO.setEnable_reset(false);
        }
        return new ResponseEntity<>(resetPasswordDTO, HttpStatus.OK);
    }

    @GetMapping(value = "/enable_category")
    @Permission(type = ResourceType.SITE, permissionLogin = true)
    @ApiOperation("是否开启项目/组织类型控制")
    public ResponseEntity<Boolean> getEnabledStateOfTheCategory() {
        return new ResponseEntity<>(systemSettingService.getEnabledStateOfTheCategory(), HttpStatus.OK);
    }
}
