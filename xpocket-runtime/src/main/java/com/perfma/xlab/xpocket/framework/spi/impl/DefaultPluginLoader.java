package com.perfma.xlab.xpocket.framework.spi.impl;

import com.perfma.xlab.xpocket.classloader.XPocketPluginClassLoader;
import com.perfma.xlab.xpocket.plugin.context.FrameworkPluginContext;
import com.perfma.xlab.xpocket.plugin.loader.PluginLoader;
import com.perfma.xlab.xpocket.spi.XPocketPlugin;
import com.perfma.xlab.xpocket.spi.command.CommandInfo;
import com.perfma.xlab.xpocket.spi.command.CommandList;
import com.perfma.xlab.xpocket.spi.command.XPocketCommand;
import com.perfma.xlab.xpocket.utils.AsciiArtUtil;
import com.perfma.xlab.xpocket.utils.StringUtils;
import com.perfma.xlab.xpocket.utils.TerminalUtil;
import com.perfma.xlab.xpocket.utils.XPocketConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @author gongyu <yin.tong@perfma.com>
 */
public class DefaultPluginLoader extends DefaultNamedObject implements PluginLoader {

    private static final String XLAB_SPI_PACKAGE = "com.perfma.xlab.xpocket.spi.";

    private static final String PLUGIN_PATH = System.getProperty("XPOCKET_PLUGIN_PATH");

    private static final Class XPOCKET_COMMAND_CLASS = XPocketCommand.class;

    private static final Class XPOCKET_PLUGIN_CLASS = XPocketPlugin.class;

    private static final String PLUGIN_UNI_FLAG_FORMAT = "%s@%s";

    private final HashMap<String, FrameworkPluginContext> pluginMap = new HashMap<>();

    @Override
    public boolean loadPlugins(String resouceName, boolean isOnLoad, Instrumentation inst) {
        return loadPlugins(resouceName);
    }

    @Override
    public boolean loadPlugins(String resourceName) {
        try {
            File pluginDir = new File(PLUGIN_PATH);
            File[] plugins = pluginDir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.endsWith(".jar");
                }
            });

            if (plugins == null || plugins.length == 0) {
                return true;
            }

            HashSet<String> nameUniIndex = new HashSet<>();
            for (File plugin : plugins) {
                XPocketPluginClassLoader pluginLoader
                        = new XPocketPluginClassLoader(
                                new URL[]{plugin.toURI().toURL()},
                                DefaultPluginLoader.class.getClassLoader());
                URL pluginDef = pluginLoader.findResource(resourceName);
                
                if(pluginDef == null) {
                    System.out.println(String.format("Error : resource %s in %s is not exist!", resourceName,plugin.getName()));
                }
                
                try (InputStreamReader reader
                        = new InputStreamReader(pluginDef.openStream(),
                                Charset.forName("UTF-8"))) {
                    Properties prop = new Properties();
                    prop.load(reader);

                    DefaultPluginContext context = new DefaultPluginContext();
                    context.setName(prop.getProperty("plugin-name"));
                    context.setNamespace(prop.getProperty("plugin-namespace"));
                    String pluginMain = prop.getProperty("main-implementation");
                    String desc = prop.getProperty("plugin-description");
                    String tips = prop.getProperty("usage-tips");
                    String plugin_author = prop.getProperty("plugin-author");
                    String plugin_project = prop.getProperty("plugin-project");
                    String plugin_version = prop.getProperty("plugin-version");
                    String tool_author = prop.getProperty("tool-author");
                    String tool_project = prop.getProperty("tool-project");
                    String tool_version = prop.getProperty("tool-version");
                    String plugin_type = prop.getProperty("plugin-type");
                    String agent_mode = prop.getProperty("agent-mode");

                    if ("on_load".equals(agent_mode)) {
                        continue;
                    }

                    //
                    if ("java_agent".equals(plugin_type)) {
                        pluginMain = null;
                    }

                    //auto handle logo
                    {
                        String name = context.getName();
                        StringBuilder text = new StringBuilder(name.length() * 2);

                        for (char c : name.toUpperCase().toCharArray()) {
                            text.append(c).append(" ");
                        }

                        context.setLogo(AsciiArtUtil.text2AsciiArt(text.toString()));
                    }

                    if (desc != null && !desc.isEmpty()) {
                        context.setDescription(desc);
                    }

                    if (tips != null && !tips.isEmpty()) {
                        context.setTips(tips);
                    }

                    String pluginInfo = "";

                    if (!StringUtils.isblank(plugin_author)) {
                        pluginInfo += "@|white plugin-author       : " + plugin_author + " |@" + TerminalUtil.lineSeparator;
                    }

                    if (!StringUtils.isblank(plugin_project)) {
                        pluginInfo += "@|white plugin-project      : " + plugin_project + " |@" + TerminalUtil.lineSeparator;
                    }

                    if (!StringUtils.isblank(plugin_version)) {
                        pluginInfo += "@|white plugin-version      : " + plugin_version + " |@" + TerminalUtil.lineSeparator;
                    }

                    if (!StringUtils.isblank(tool_author)) {
                        pluginInfo += "@|white tools-author        : " + tool_author + " |@" + TerminalUtil.lineSeparator;
                    }

                    if (!StringUtils.isblank(tool_project)) {
                        pluginInfo += "@|white tool-project        : " + tool_project + " |@" + TerminalUtil.lineSeparator;
                    }

                    if (!StringUtils.isblank(tool_version)) {
                        pluginInfo += "@|white tool-version        : " + tool_version + " |@" + TerminalUtil.lineSeparator;
                    }

                    context.setPluginInfo(pluginInfo);

                    if (pluginMain != null) {
                        context.setPluginClass(pluginLoader.loadClass(pluginMain));
                    }
                    String commandPackage = prop.getProperty("plugin-command-package");
                    //scan classes
                    List<String> classNames = new ArrayList<>();
                    try (ZipInputStream jarIs = new ZipInputStream(
                            new FileInputStream(plugin))) {

                        for (ZipEntry e = jarIs.getNextEntry(); e != null;
                                e = jarIs.getNextEntry()) {
                            if (!e.isDirectory() && e.getName().endsWith(".class")) {
                                String className = e.getName().replace('/', '.')
                                        .substring(0, e.getName().lastIndexOf("."));
                                if ((commandPackage == null
                                        || className.startsWith(commandPackage))
                                        && !className.startsWith(XLAB_SPI_PACKAGE)) {
                                    classNames.add(className);
                                }
                            }
                        }
                    }

                    //use XPocketPluginClassLoader
                    ClassLoader currentTCL = Thread.currentThread()
                            .getContextClassLoader();
                    HashMap<String, DefaultCommandContext> cmdMap = new HashMap<>();
                    try {
                        try {
                            Thread.currentThread().setContextClassLoader(pluginLoader);
                            for (String commandClassName : classNames) {
                                Class commandClass = pluginLoader.loadClass(commandClassName);
                                if (XPOCKET_COMMAND_CLASS.isAssignableFrom(commandClass)) {
                                    //collect commandinfo information
                                    CommandInfo[] infos
                                            = (CommandInfo[]) commandClass
                                                    .getAnnotationsByType(
                                                            CommandInfo.class);
                                    
                                    //collect commandlist infomation
                                    CommandList[] lists
                                            = (CommandList[]) commandClass
                                                    .getAnnotationsByType(
                                                            CommandList.class);
                                    //优先判断是否存在注解
                                    if((infos == null || infos.length == 0) 
                                            && (lists == null || lists.length == 0)){   
                                        continue; 
                                    }
                                    
                                    XPocketCommand commandObject
                                            = (XPocketCommand) commandClass.getConstructor()
                                                    .newInstance();
                                    
                                    for (CommandInfo info : infos) {
                                        cmdMap.put(info.name(),
                                                new DefaultCommandContext(info.name(),info.shortName(),
                                                        info.usage(), info.index(),
                                                        "java_agent".equals(plugin_type)
                                                        ? XPocketConstants.DEFAULT_ADAPTOR
                                                        : commandObject));
                                    }

                                    
                                    for (CommandList list : lists) {
                                        String[] names = list.names();
                                        String[] usages = list.usage();

                                        for (int i = 0; i < names.length; i++) {
                                            cmdMap.put(names[i],
                                                    new DefaultCommandContext(names[i],null,
                                                            usages.length > i
                                                                    ? usages[i]
                                                                    : "", 50,
                                                            "java_agent".equals(plugin_type)
                                                            ? XPocketConstants.DEFAULT_ADAPTOR
                                                            : commandObject));
                                        }
                                    }
                                }
//                                else if (XPOCKET_PLUGIN_CLASS.isAssignableFrom(commandClass)
//                                        && pluginMain == null) {
//                                    context.setPluginClass(commandClass);
//                                }
                            }
                        } finally {
                            Thread.currentThread().setContextClassLoader(currentTCL);
                        }

                        context.setCommands(cmdMap);

                        if (nameUniIndex.contains(context.getName())) {
                            pluginMap.remove(context.getName());
                        } else {
                            pluginMap.put(context.getName(), context);
                            nameUniIndex.add(context.getName());
                        }

                        pluginMap.put(String.format(PLUGIN_UNI_FLAG_FORMAT,
                                context.getName(), context.getNamespace().toUpperCase()),
                                context);
                    } catch (Throwable ex) {
                        System.err.println(String.format("load plugin %s class %s error :\n",
                                context.getName(),context.getPluginClass()));
                        ex.printStackTrace(System.err);
                    }
                }
            }
        } catch (Throwable ex) {
            ex.printStackTrace();
            return false;
        }

        return true;
    }

    @Override
    public Set<FrameworkPluginContext> getAvailablePlugins() {
        return new HashSet<>(pluginMap.values());
    }

    @Override
    public Set<FrameworkPluginContext> getAllPlugins() {
        return new HashSet<>(pluginMap.values());
    }

    @Override
    public void addPlugin(FrameworkPluginContext pluginContext) {
        if (!pluginMap.containsKey(pluginContext.getName())) {
            pluginMap.put(pluginContext.getName(), pluginContext);
        }
        pluginMap.put(String.format(PLUGIN_UNI_FLAG_FORMAT, pluginContext.getName(),
                pluginContext.getNamespace().toUpperCase()), pluginContext);
    }

    @Override
    public FrameworkPluginContext getPlugin(String name, String namespace) {
        if (namespace == null) {
            return pluginMap.get(name);
        }
        return pluginMap.get(String.format(PLUGIN_UNI_FLAG_FORMAT, name,
                namespace.toUpperCase()));
    }
}
