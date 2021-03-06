package com.github.wangji92.arthas.plugin.action.arthas;

import com.aliyun.oss.OSS;
import com.github.wangji92.arthas.plugin.common.exception.CompilerFileNotFoundException;
import com.github.wangji92.arthas.plugin.constants.ArthasCommandConstants;
import com.github.wangji92.arthas.plugin.setting.AppSettingsState;
import com.github.wangji92.arthas.plugin.utils.*;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.BaseEncoding;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.task.ProjectTaskManager;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.FalseFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.codehaus.groovy.runtime.StackTraceUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.Jedis;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @author 汪小哥
 * @date 16-08-2020
 */
public class ArthasHotRedefineCommandAction extends AnAction implements DumbAware {
    private static final Logger LOG = Logger.getInstance(ArthasHotRedefineCommandAction.class);

    public ArthasHotRedefineCommandAction() {
        this.setEnabledInModalContext(true);
    }

    private static final String RETRANSFORM_NOTE = "【retransform 增强后 stop/rest 不影响,先删除retransform entry,显式触发 retransform 失效】【不能修改、添加、删除类的field和method】";

    private static final String REDEFINE_NOTE = "【redefine 增强后 stop/rest 不影响,watch/jad/trace 等等增强后失效】【不能修改、添加、删除类的field和method】";


    /**
     * oss 获取到链接
     */
    private static final String OSS_HOT_REDEFINE = "curl -Lk  \"%s\" | base64 --decode >arthas-idea-plugin-hot-swap.sh;chmod a+x arthas-idea-plugin-hot-swap.sh;./arthas-idea-plugin-hot-swap.sh;";
    /**
     * 剪切板处理字符串
     */
    private static final String CLIPBOARD_HOT_REDEFINE = "echo \"%s\" |base64 --decode >arthas-idea-plugin-hot-swap.sh;chmod a+x arthas-idea-plugin-hot-swap.sh;./arthas-idea-plugin-hot-swap.sh;";

    /**
     * redis
     */
    private static final String REDIS_HOT_REDEFINE = "echo `redis-cli -h '%s' -p %s  get %s`|base64 --decode >arthas-idea-plugin-hot-swap.sh;chmod a+x arthas-idea-plugin-hot-swap.sh;./arthas-idea-plugin-hot-swap.sh;";

    @Override
    public void update(@NotNull AnActionEvent e) {
        super.update(e);
        DataContext dataContext = e.getDataContext();
        Project project = CommonDataKeys.PROJECT.getData(dataContext);
        if (project == null) {
            e.getPresentation().setEnabled(false);
            return;
        }
        //右侧选择了一个或者多个文件
        VirtualFile[] virtualFileFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (virtualFileFiles == null) {
            e.getPresentation().setEnabled(false);
            return;
        }
        List<PsiFile> psiFileJavaFiles = Arrays.stream(virtualFileFiles).map(PsiManager.getInstance(project)::findFile).filter(psiFileElement -> psiFileElement instanceof PsiJavaFile).collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(psiFileJavaFiles)) {
            e.getPresentation().setEnabled(true);
            return;
        }
        e.getPresentation().setEnabled(false);
    }

    /**
     * @param event
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        DataContext dataContext = event.getDataContext();
        Project project = CommonDataKeys.PROJECT.getData(dataContext);
        if (project == null) {
            return;
        }
        String hotCommand = "retransform";
        //4.6.1 升级 retransform https://github.com/alibaba/arthas/issues/1651
        if ("Redefine".equals(event.getPresentation().getDescription())) {
            hotCommand = "redefine";
        }

        VirtualFile[] virtualFileFiles = CommonDataKeys.VIRTUAL_FILE_ARRAY.getData(dataContext);
        PsiElement psiElement = CommonDataKeys.PSI_ELEMENT.getData(dataContext);
        assert virtualFileFiles != null;

        String finalHotCommand = hotCommand;
        Runnable runnable = () -> {
            List<String> fullClassPackagePaths = Lists.newArrayList();
            try {
                fullClassPackagePaths = this.getAllFullTargetClassFilePath(project, virtualFileFiles, psiElement);
            } catch (Exception e) {
                if (e instanceof CompilerFileNotFoundException) {
                    LOG.warn("maybe compiler error", e);
                    NotifyUtils.notifyMessage(project, "查询编译后的文件错误:一般都是没有编译的错误,(建议先编译整个工程,打开热更新先编译),在target class 目录找不到文件 [" + e.getMessage() + "]", NotificationType.ERROR);
                } else {
                    LOG.error("maybe compiler error", e);
                    NotifyUtils.notifyMessage(project, "查询编译后的文件错误:一般都是没有编译的错误,(建议先编译整个工程,打开热更新先编译)", NotificationType.ERROR);
                }
                return;
            }
            if (CollectionUtils.isEmpty(fullClassPackagePaths)) {
                NotifyUtils.notifyMessage(project, "没有找到目标文件编译后的class文件", NotificationType.ERROR);
                return;
            }
            List<String> bash64FileAndPathList = Lists.newArrayList();

            List<String> shellOutPaths = Lists.newArrayList();

            try {
                fullClassPackagePaths.forEach(fullClassPackagePath -> {
                    File file = new File(fullClassPackagePath);
                    if (!file.exists()) {
                        return;
                    }
                    String classBase64 = IoUtils.readFileToBase64String(file);
                    // 内部类 的时候回有问题 展示上面 结果没有影响 这里修改一下，windows的文件描述符 和 linux的不一样，最后的生成的路径要修改一下
                    String pathReplaceAll = fullClassPackagePath.substring(fullClassPackagePath.indexOf(File.separator + "target" + File.separator + "classes") + 15)
                            //需要将Windows的文件描述符转换为Linux的，最后一个多余了/.class要转换回来
                            .replace(File.separator, "/").replace("/.class", ".class")
                            // https://github.com/WangJi92/arthas-idea-plugin/issues/23 为什么要转义 shell 脚本执行的时候这个字符特殊不能直接使用
                            // 最初使用 replace("$","\\$") mac 没有问题  windows有问题

                            // https://blog.csdn.net/xrt95050/article/details/6651571 替换$ 为 \$
                            // 要把 $ 替换成 \$ ，则要使用 \\\\\\& 来替换，因为一个 \ 要使用 \\\ 来进行替换，
                            // 而一个 $ 要使用 \\$ 来进行替换，因 \ 与  $ 在作为替换内容时都属于特殊字符：$ 字符表示反向引用组，而 \ 字符又是用来转义 $ 字符的
                            .replaceAll("\\$", "\\\\\\$");

                    String pathAndClass = classBase64 + "|" + ArthasCommandConstants.HOT_SWAMP_BASH_PACKAGE_PATH + pathReplaceAll;
                    shellOutPaths.add(ArthasCommandConstants.HOT_SWAMP_BASH_PACKAGE_PATH + pathReplaceAll);
                    bash64FileAndPathList.add(pathAndClass);
                });

                if (bash64FileAndPathList.size() <= 0) {
                    NotifyUtils.notifyMessage(project, "当前选择对于的类文件在target目录.class文件不存在,请编译", NotificationType.ERROR);
                    return;
                }


                AppSettingsState settings = AppSettingsState.getInstance(project);
                String selectProjectName = settings.selectProjectName;

                //不为空就删除
                String deleteClassFile = settings.hotRedefineDelete ? "delete" : "";
                if (settings.manualSelectPid) {
                    // 手动选择pid
                    selectProjectName = "";
                } else if (StringUtils.isBlank(selectProjectName)) {
                    NotifyUtils.notifyMessage(project, "必须配置才能使用 jps -l 查看名称,hot swap use project name select process and batch support; as.sh --select projectName -c 'redefine /tmp/test.class'", NotificationType.ERROR);
                    return;
                }
                String arthasIdeaPluginBase64AndPathCommand = String.join(",", bash64FileAndPathList);
                String arthasIdeaPluginRedefineCommand = finalHotCommand + " " + String.join(" ", shellOutPaths);
                Map<String, String> params = Maps.newHashMap();
                params.put("arthasIdeaPluginBase64AndPathCommand", arthasIdeaPluginBase64AndPathCommand);
                params.put("arthasIdeaPluginRedefineCommand", arthasIdeaPluginRedefineCommand);
                params.put("arthasIdeaPluginApplicationName", selectProjectName);
                params.put("deleteClassFile", deleteClassFile);


                String redefineSh = StringUtils.stringSubstitutor("/template/arthas-idea-plugin-hot-swap.sh", params);

                String base64RedefineSh = BaseEncoding.base64().encode(redefineSh.getBytes());

                String command = "";
                StringBuilder tipsBuilder = new StringBuilder("到服务器粘贴执行脚本无需打开arthas");
                if ("redefine".equals(finalHotCommand)) {
                    tipsBuilder.append(REDEFINE_NOTE);
                } else {
                    tipsBuilder.append(RETRANSFORM_NOTE);
                }
                if (settings.aliYunOss) {
                    this.uploadBase64FileToOss(project, settings, base64RedefineSh, tipsBuilder.toString());
                } else if (settings.hotRedefineRedis) {
                    tipsBuilder.append("【服务器服务器需要有redis cli命令】");
                    this.uploadBase64FileToRedis(project, settings, base64RedefineSh, tipsBuilder.toString());
                } else {
                    command = String.format(CLIPBOARD_HOT_REDEFINE, base64RedefineSh);
                    ClipboardUtils.setClipboardString(command);
                    tipsBuilder.append("【由于没有使用其他存储 执行的脚本比较长,推荐配置阿里云oss or redis】");
                    NotifyUtils.notifyMessage(project, tipsBuilder.toString());
                }
            } catch (Exception e) {
                LOG.error("未知错误", e);
                NotifyUtils.notifyMessage(project, "未知错误", NotificationType.ERROR);
            }
        };

        // https://stackoverflow.com/questions/18725340/create-a-background-task-in-intellij-plugin
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Hot Swap") {
            @Override
            public void run(@NotNull ProgressIndicator progressIndicator) {
                // Set the progress bar percentage and text
                try {
                    progressIndicator.setIndeterminate(false);
                    progressIndicator.setFraction(0.10);
                    progressIndicator.setText("90% to compile select file");
                    AppSettingsState settings = AppSettingsState.getInstance(project);
                    if (settings.redefineBeforeCompile) {
                        // 这里的版本有兼容性问题 目前占时可以使用
                        // Deprecated method ProjectTaskManager.compile(...) is invoked in ArthasHotRedefineCommandAction$1.run(...). This method will be removed in 2020.1
                        if (ApplicationInfo.getInstance().getBuild().getBaselineVersion() <= 201) {
                            //2018.2 编译报错
                            WriteActionCompatibleUtils.runAndWait(() -> {
                                ProjectTaskManager.getInstance(project).compile(virtualFileFiles, projectTaskResult -> {
                                    if (projectTaskResult.getErrors() > 0) {
                                        NotifyUtils.notifyMessage(project, "Java文件编译编译错误 请处理。(最好热更新之前确保最少编译好一次整个工程)这里只会局部编译当前文件", NotificationType.ERROR);
                                        progressIndicator.cancel();
                                        return;
                                    }
                                    if (progressIndicator.isCanceled() || projectTaskResult.isAborted()) {
                                        NotifyUtils.notifyMessage(project, "任务已经取消");
                                        return;
                                    }
                                    WriteActionCompatibleUtils.runAndWait(runnable::run);
                                });
                            });

                        } else {
                            WriteActionCompatibleUtils.runAndWait(() -> {
                                ProjectTaskManager instance = ProjectTaskManager.getInstance(project);

                                Object promise = MethodUtils.invokeMethod(instance, "compile", new Object[]{virtualFileFiles}, new Class[]{VirtualFile[].class});
                                MethodUtils.invokeMethod(promise, "onSuccess", (Consumer) o -> {
                                    WriteActionCompatibleUtils.runAndWait(runnable::run);
                                });
                            });
                        }
                    } else {
                        WriteActionCompatibleUtils.runAndWait(runnable::run);

                    }

                } catch (Exception e) {
                    LOG.error("record arthas hot swap error", e);
                    NotifyUtils.notifyMessage(project, "热更新未知错误", NotificationType.ERROR);
                    try {
                        WriteActionCompatibleUtils.runAndWait(runnable::run);
                    } catch (Exception ex) {
                        LOG.error("record arthas hot swap try again error", ex);
                        NotifyUtils.notifyMessage(project, "热更新未知错误", NotificationType.ERROR);
                    }
                }

            }
        });
    }

    /**
     * 保存数据上传到redis
     *
     * @param project
     * @param settings
     * @param base64RedefineSh
     */
    private void uploadBase64FileToRedis(Project project, AppSettingsState settings, String base64RedefineSh, String tips) {
        try (Jedis jedis = JedisUtils.buildJedisClient(settings.redisAddress, settings.redisPort, 5000, settings.redisAuth)) {
            String command;
            StringBuilder portAndAuth = new StringBuilder("" + settings.redisPort);
            if (!StringUtils.isBlank(settings.redisAuth)) {
                portAndAuth.append(" -a ").append(settings.redisAuth);
            }

            String cacheKey = settings.redisCacheKey + "_" + UUID.randomUUID().toString();
            jedis.setex(cacheKey, settings.redisCacheKeyTtl, base64RedefineSh);
            command = String.format(REDIS_HOT_REDEFINE, settings.redisAddress, portAndAuth, cacheKey);
            ClipboardUtils.setClipboardString(command);
            NotifyUtils.notifyMessage(project, tips);
        } catch (Exception e) {
            LOG.error("record arthas hot swap upload to redis error", e);
            NotifyUtils.notifyMessage(project, "上传文件到redis 失败" + e.getMessage(), NotificationType.ERROR);
        }
    }

    /**
     * 上传热更新 文件到oss
     *
     * @param project
     * @param settings
     * @param base64RedefineSh
     * @param tips
     */
    private void uploadBase64FileToOss(Project project, AppSettingsState settings, String base64RedefineSh, String tips) {
        String command;
        OSS oss = null;
        try {
            oss = AliyunOssUtils.buildOssClient(project);
            String filePathKey = settings.directoryPrefix + UUID.randomUUID().toString();
            String urlEncodeKeyPath = AliyunOssUtils.putFile(oss, settings.bucketName, filePathKey, base64RedefineSh);
            String presignedUrl = AliyunOssUtils.generatePresignedUrl(oss, settings.bucketName, urlEncodeKeyPath, new Date(System.currentTimeMillis() + 3600L * 1000));
            command = String.format(OSS_HOT_REDEFINE, presignedUrl);
            ClipboardUtils.setClipboardString(command);
            NotifyUtils.notifyMessage(project, tips);
        } catch (Exception e) {
            LOG.error("record arthas hot swap upload to oss error", e);
            StackTraceUtils.printSanitizedStackTrace(e);
            NotifyUtils.notifyMessage(project, "上传文件到oss 失败" + e.getMessage(), NotificationType.ERROR);
        } finally {
            if (oss != null) {
                oss.shutdown();
            }
        }
    }

    @Nullable
    private List<String> getAllFullTargetClassFilePath(Project project, VirtualFile[] virtualFileFiles, PsiElement psiElement) {
        List<String> fullClassPackagePaths = Lists.newArrayList();
        if (virtualFileFiles.length == 1 && OgnlPsUtils.isPsiFieldOrMethodOrClass(psiElement)) {
            //选择 当个文件 且为 编辑区选择的

            String packageName = ((PsiJavaFile) psiElement.getContainingFile()).getPackageName();
            String className = FilenameUtils.getBaseName(psiElement.getContainingFile().getName());
            String ideaClassName = packageName + "." + className;

            //主要是根据模块查询 当前编译后的路径的信息
            final String compilerOutputPath = OgnlPsUtils.getCompilerOutputPath(project, ideaClassName);

            //全路径包含 匿名类的处理
            String pathClassName = OgnlPsUtils.getCommonOrInnerOrAnonymousClassName(psiElement);

            String packageNamePath = packageName.replace(".", File.separator);
            //处理内部类 匿名类获取class的问题
            boolean isAnonymousClass = pathClassName.contains("*$*");
            if (isAnonymousClass) {
                // 匿名类要处理遍历
                String outClassName = FilenameUtils.getBaseName(psiElement.getContainingFile().getName());
                // 查找当前类下面的所有的匿名类的信息
                File packageDirFile = new File(compilerOutputPath + File.separator + packageNamePath);
                if (!packageDirFile.exists()) {
                    throw new CompilerFileNotFoundException(String.format("compiler target class dir not found,dir:%s", compilerOutputPath + File.separator + packageNamePath));
                }
                List<File> files = Lists.newArrayList(FileUtils.listFiles(packageDirFile, new RegexFileFilter("^(" + outClassName + "\\$).*(\\d\\.class)$"), FalseFileFilter.INSTANCE));
                fullClassPackagePaths = files.stream().map(file -> String.format("%s%s%s", compilerOutputPath + File.separator, packageNamePath + File.separator, file.getName())).collect(Collectors.toList());
            } else {
                // this is maybe inner class
                String currentClassName = pathClassName.replace(packageName + ".", "").replace("$", "\\$");
                File packageDirFile = new File(compilerOutputPath + File.separator + packageNamePath);
                if (!packageDirFile.exists()) {
                    throw new CompilerFileNotFoundException(String.format("compiler target class dir not found,dir:%s", compilerOutputPath + File.separator + packageNamePath));
                }
                List<File> files = Lists.newArrayList(FileUtils.listFiles(new File(compilerOutputPath + File.separator + packageNamePath), new RegexFileFilter("^(" + currentClassName + "\\$).*\\.class$"), FalseFileFilter.INSTANCE));
                List<String> currentClassFullPaths = files.stream().map(file -> String.format("%s%s%s", compilerOutputPath + File.separator, packageNamePath + File.separator, file.getName())).collect(Collectors.toList());

                //add current class
                fullClassPackagePaths.addAll(currentClassFullPaths);
                String path = compilerOutputPath + File.separator + pathClassName.replace(".", File.separator) + ".class";
                fullClassPackagePaths.add(path);

            }

        } else {
            //  https://blog.csdn.net/weixin_34223655/article/details/88112593
            // PsiFile 转 VirtualFile
            List<PsiFile> psiFileJavaFiles = Arrays.stream(virtualFileFiles).map(PsiManager.getInstance(project)::findFile).filter(psiFileElement -> psiFileElement instanceof PsiJavaFile).collect(Collectors.toList());

            if (CollectionUtils.isEmpty(psiFileJavaFiles)) {
                NotifyUtils.notifyMessage(project, "请选择.java 先编译 Control /Command F9 编译 或者 .class文件", NotificationType.ERROR);
                return null;
            }
            fullClassPackagePaths = psiFileJavaFiles.stream().flatMap(psiFileJavaFile -> {
                String packageNameBack = ((PsiJavaFile) psiFileJavaFile.getContainingFile()).getPackageName();
                String packageNamePath = packageNameBack.replace(".", File.separator);
                String className = FilenameUtils.getBaseName(psiFileJavaFile.getContainingFile().getName());
                String qualifiedName = packageNameBack + "." + className;
                String qualifiedNamePath = qualifiedName.replace(".", File.separator);
                String currentCompilerOutputPath = OgnlPsUtils.getCompilerOutputPath(project, qualifiedName);
                File packageDirFile = new File(currentCompilerOutputPath + File.separator + packageNamePath);
                if (!packageDirFile.exists()) {
                    throw new CompilerFileNotFoundException(String.format("compiler target class dir not found,dir:%s", currentCompilerOutputPath + File.separator + packageNamePath));
                }
                List<File> files = Lists.newArrayList(FileUtils.listFiles(new File(currentCompilerOutputPath + File.separator + packageNamePath), new RegexFileFilter("^(" + className + "\\$).*\\.class$"), FalseFileFilter.INSTANCE));
                List<String> currentClassFullPaths = files.stream().map(file -> String.format("%s%s%s", currentCompilerOutputPath + File.separator, packageNamePath + File.separator, file.getName())).collect(Collectors.toList());
                currentClassFullPaths.add(currentCompilerOutputPath + File.separator + qualifiedNamePath + ".class");
                return currentClassFullPaths.stream();
            }).distinct().collect(Collectors.toList());

        }
        return fullClassPackagePaths;
    }


}

