@echo off
rem ***********
rem Basic start script for ThreadLogic for Windows
rem ***********
rem If you have big log file you might need to adjust Xmx setting

java -Xmx256m -jar "%~dp0/../threadlogic.jar"
