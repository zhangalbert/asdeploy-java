package com.ablesky.asdeploy.controller;

import java.io.File;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.Transformer;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import com.ablesky.asdeploy.dao.base.QueryParamMap;
import com.ablesky.asdeploy.dto.ConflictInfoDto;
import com.ablesky.asdeploy.pojo.DeployItem;
import com.ablesky.asdeploy.pojo.DeployLock;
import com.ablesky.asdeploy.pojo.DeployRecord;
import com.ablesky.asdeploy.pojo.PatchFileRelGroup;
import com.ablesky.asdeploy.pojo.PatchGroup;
import com.ablesky.asdeploy.pojo.Project;
import com.ablesky.asdeploy.service.IDeployService;
import com.ablesky.asdeploy.service.IPatchGroupService;
import com.ablesky.asdeploy.service.IProjectService;
import com.ablesky.asdeploy.util.AuthUtil;
import com.ablesky.asdeploy.util.DeployUtil;
import com.ablesky.asdeploy.util.Deployer;

@Controller
@RequestMapping("/deploy")
public class DeployController {
	
	@Autowired
	private IProjectService projectService;
	@Autowired
	private IDeployService deployService;
	@Autowired
	private IPatchGroupService patchGroupService;
	
	@RequestMapping("/initOption/{msg}")
	public String initOption(@PathVariable("msg") String msg, Model model) {
		if("paramsError".equals(msg)) {
			model.addAttribute("errorMessage", "输入参数有误!");
		}
		return initOption(model);
	}
	
	@RequestMapping("/initOption")
	public String initOption(Model model) {
		List<Project> projectList = projectService.getProjectListResult(0, 0, QueryParamMap.EMPTY_MAP);
		model.addAttribute("projectList", projectList);
		return "deploy/initOption";
	}
	
	@RequestMapping(value = "/toDeployPage", method = RequestMethod.POST)
	public String toDeployPage(
			String deployType,
			String version,
			Long projectId,
			@RequestParam(required=false)
			Long patchGroupId,
			Model model) {
		Project project = null;
		PatchGroup patchGroup = null;
		if(StringUtils.isBlank(deployType) || StringUtils.isBlank(version) 
				|| projectId == null || projectId == 0
				|| (project = projectService.getProjectById(projectId)) == null ) {
			return "redirect:/deploy/initOption/paramsError";
		}
		DeployLock lock = deployService.checkCurrentLock();
		if(lock != null) {
			return "redirect:/main";
		}
		if(patchGroupId != null && patchGroupId > 0) {
			patchGroup = patchGroupService.getPatchGroupById(patchGroupId);
		}
		
		DeployRecord deployRecord = buildNewDeployRecord(project);
		deployService.saveOrUpdateDeployRecord(deployRecord);
		DeployLock newLock = buildNewDeployLock(deployRecord);
		deployService.saveOrUpdateDeployLock(newLock);
		
		model.addAttribute("project", project)
			.addAttribute("deployType", deployType)
			.addAttribute("version", version)
			.addAttribute("patchGroup", patchGroup)
			.addAttribute("deployRecord", deployRecord);
		return "deploy/deployPage";
	}
	
	private DeployRecord buildNewDeployRecord(Project project) {
		DeployRecord deployRecord = new DeployRecord();
		deployRecord.setUser(AuthUtil.getCurrentUser());
		deployRecord.setProject(project);
		deployRecord.setCreateTime(new Timestamp(System.currentTimeMillis()));
		deployRecord.setIsConflictWithOthers(false);
		deployRecord.setStatus(DeployRecord.STATUS_PREPARE);
		return deployRecord;
	}
	
	private DeployLock buildNewDeployLock(DeployRecord deployRecord) {
		DeployLock lock = new DeployLock();
		lock.setUser(AuthUtil.getCurrentUser());
		lock.setDeployRecord(deployRecord);
		lock.setLockedTime(new Timestamp(System.currentTimeMillis()));
		lock.setIsLocked(Boolean.TRUE);
		return lock;
	}
	
	/**
	 * 在deployService.unlockDeploy方法中判断解锁权限
	 * 没有权限自然解不开
	 */
	@RequestMapping("/unlockDeploy")
	public @ResponseBody Map<String, Object> unlockDeploy() {
		deployService.unlockDeploy();
		ModelMap resultMap = new ModelMap();
		return resultMap.addAttribute("success", true);
	}
	
	@RequestMapping("/unlockDeployRedirect")
	public String unlockDeployRedirect() {
		deployService.unlockDeploy();
		return "redirect:/main";
	}
	
	@RequestMapping("/uploadStaticTar")
	public @ResponseBody Map<String, Object> uploadStaticTar(
			Long projectId,
			String version,
			MultipartFile staticTarFile) throws IllegalStateException, IOException {
		ModelMap resultMap = new ModelMap();
		String filename = staticTarFile.getOriginalFilename();
		Project project = projectService.getProjectById(projectId);
		if(project == null) {
			return resultMap.addAttribute("success", false).addAttribute("message", "项目不存在!");
		}
		staticTarFile.transferTo(new File(DeployUtil.getDeployItemUploadFolder(project.getName(), version) + filename));
		return resultMap
				.addAttribute("filename", filename)
				.addAttribute("size", staticTarFile.getSize())
				.addAttribute("success", true);
	}
	
	@RequestMapping("/uploadItem")
	public @ResponseBody Map<String, Object> uploadItem(
			Long projectId,
			Long deployRecordId,
			@RequestParam(defaultValue = "0")
			Long patchGroupId,
			String deployType,
			String version,
			@RequestParam("deployItemField")
			MultipartFile deployItemFile
			) throws IllegalStateException, IOException {
		ModelMap resultMap = new ModelMap();
		String filename = deployItemFile.getOriginalFilename();
		Project project = projectService.getProjectById(projectId);
		DeployRecord deployRecord = deployService.getDeployRecordById(deployRecordId);
		PatchGroup patchGroup = null;
		if(patchGroupId != null && patchGroupId > 0) {
			patchGroup = patchGroupService.getPatchGroupById(patchGroupId);
			if(patchGroup == null) {
				return resultMap.addAttribute("success", false).addAttribute("message", "补丁组不存在!");
			}
			if(!filename.contains(patchGroup.getCheckCode())) {
				return resultMap.addAttribute("success", false).addAttribute("message", "补丁名称与补丁组的标识号不匹配!");
			}
		}
		deployService.persistDeployItem(deployItemFile, project, patchGroup, deployRecord, deployType, version);
		return resultMap
				.addAttribute("filename", filename)
				.addAttribute("size", deployItemFile.getSize())
				.addAttribute("success", true);
	}
	
	@RequestMapping(value="/decompressItem", method=RequestMethod.POST)
	public @ResponseBody Map<String, Object> decompressItem(
			Long deployRecordId,
			@RequestParam(defaultValue="0")
			Long patchGroupId
			) throws IOException {
		ModelMap resultMap = new ModelMap();
		DeployRecord deployRecord = deployService.getDeployRecordById(deployRecordId);
		DeployItem deployItem = deployRecord.getDeployItem();
		if(deployItem == null) {
			return resultMap.addAttribute("success", false).addAttribute("message", "压缩文件不存在!");
		}
		try {
			DeployUtil.unzipDeployItem(deployItem);
		} catch (IOException e) {
			e.printStackTrace();
			return resultMap.addAttribute("success", false).addAttribute("message", "文件解压缩失败!");
		}
		String targetFolderPath = FilenameUtils.concat(deployItem.getFolderPath(), FilenameUtils.getBaseName(deployItem.getFileName()));
		List<String> filePathList = DeployUtil.getDeployItemFilePathList(targetFolderPath);
		if(CollectionUtils.isEmpty(filePathList)) {
			return resultMap.addAttribute("success", false).addAttribute("message", "解压后的文件夹中无内容! 请确认压缩包文件名与被压缩的目录名是否一致!");
		}
		List<ConflictInfoDto> conflictInfoList = Collections.emptyList();
		if(patchGroupId != null && patchGroupId > 0) {
			final PatchGroup patchGroup = patchGroupService.getPatchGroupById(patchGroupId);
			if(patchGroup != null) {
				List<PatchFileRelGroup> conflictRelList = patchGroupService.getPatchFileRelGroupListWhichConflictWith(patchGroup, filePathList);
				conflictInfoList = new ArrayList<ConflictInfoDto>(CollectionUtils.collect(conflictRelList, new Transformer<PatchFileRelGroup, ConflictInfoDto>() {
					@Override
					public ConflictInfoDto transform(PatchFileRelGroup conflictRel) {
						return new ConflictInfoDto().fillDto(patchGroup, conflictRel);
					}
				}));
			}
		}
		return resultMap
				.addAttribute("filePathList", filePathList)
				.addAttribute("conflictInfoList", conflictInfoList)
				.addAttribute("readme", DeployUtil.loadReadmeContent(targetFolderPath))
				.addAttribute("success", true);
	}
	
	/**
	 * @param deployRecordId
	 * @param deployManner	"发布(deploy)"或"回滚(rollback)"
	 * @return
	 */
	@RequestMapping(value="/startDeploy", method=RequestMethod.POST)
	public @ResponseBody Map<String, Object> startDeploy(
			Long deployRecordId, 
			@RequestParam(defaultValue = "0")
			Long patchGroupId,
			String deployManner,
			@RequestParam(defaultValue="a")
			String serverGroupParam) {
		ModelMap resultMap = new ModelMap();
		DeployRecord deployRecord = null;
		PatchGroup patchGroup = null;
		File deployPatchScript = new File(DeployUtil.getDeployPatchScriptPath());
		if(!deployPatchScript.isFile()) {	// 粗略的判断下，主要是应对d盘未挂载的情形。
			return resultMap.addAttribute("success", false).addAttribute("message", "发布脚本不存在!");
		}
		if(deployRecordId == null || deployRecordId <= 0 || (deployRecord = deployService.getDeployRecordById(deployRecordId)) == null) {
			return resultMap.addAttribute("success", false).addAttribute("message", "参数有误!");
		}
		DeployLock lock = deployService.checkCurrentLock();
		if(lock == null || !lock.getDeployRecord().getId().equals(deployRecordId)) {
			return resultMap.addAttribute("success", false).addAttribute("message", "本次发布已被解锁!");
		}
		if(DeployRecord.STATUS_PREPARE.equals(deployRecord.getStatus())) {
			return resultMap.addAttribute("success", false).addAttribute("message", "尚未上传文件");
		}
		if(Boolean.TRUE.equals(Deployer.getLogIsWriting(deployRecordId))) { // 发布仍在继续
			return resultMap.addAttribute("success", false).addAttribute("message", "发布仍在继续中...");
		}
		if(patchGroupId != null && patchGroupId > 0) {
			patchGroup = patchGroupService.getPatchGroupById(patchGroupId);
		}
		// 开始发布
		doDeploy(deployRecord, patchGroup, deployManner, serverGroupParam);
		return resultMap.addAttribute("success", true).addAttribute("message", "发布启动成功!");
	}
	
	private void doDeploy(DeployRecord deployRecord, PatchGroup patchGroup, String deployManner, String serverGroupParam) {
		// 1. 记录补丁组及冲突信息
		DeployItem item = deployRecord.getDeployItem();
		String targetFolderPath = FilenameUtils.concat(item.getFolderPath(), FilenameUtils.getBaseName(item.getFileName()));
		List<String> filePathList = DeployUtil.getDeployItemFilePathList(targetFolderPath);
		deployService.persistInfoBeforeDeployStart(deployRecord, patchGroup, filePathList, deployManner);
		Deployer.executor.submit(new Deployer(deployRecord, deployManner, serverGroupParam));
	}
	
	@RequestMapping("/readDeployLogOnRealtime")
	public @ResponseBody Map<String, Object> readDeployLogOnRealtime(Long deployRecordId) {
		ModelMap resultMap = new ModelMap();
		Boolean isWriting = Deployer.getLogIsWriting(deployRecordId);
		if(isWriting == null) {	// 发布已结束，并且前面已经读完了所有日志
			resultMap.addAttribute("isFinished", true)
				.addAttribute("deployResult", Deployer.getDeployResult(deployRecordId));
			Deployer.deleteDeployResult(deployRecordId);
			Deployer.deleteLogLastReadPos(deployRecordId);
			return resultMap;
		}
		if(Boolean.FALSE.equals(isWriting)) {	// 说明发布结束了，需要最后再读一次日志信息
			Deployer.deleteLogIsWriting(deployRecordId);
		}
		String deployLogContent = DeployUtil.readDeployLogContent(deployRecordId);// 此处读日志的时候，就在不断更新文件指针了
		return resultMap.addAttribute("isFinished", false)
				.addAttribute("deployLogContent", deployLogContent);	
	}
	
}
