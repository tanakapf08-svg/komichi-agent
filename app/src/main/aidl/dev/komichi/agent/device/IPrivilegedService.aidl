package dev.komichi.agent.device;

interface IPrivilegedService {
    String diagnostics() = 0;
    String forceStop(String packageName) = 1;
    void destroy() = 16777114;
}
