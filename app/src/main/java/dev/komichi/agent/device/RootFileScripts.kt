package dev.komichi.agent.device

/** Only these fixed script templates are reachable from the structured file tools. */
internal object RootFileScripts {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    private fun render(template: String, values: Map<String,String>): String = template.trimIndent()
        .replace("§", "$" ).replace(Regex("__[A-Z][A-Z_]*__")) { values.getValue(it.value) }
    private const val SOURCE = """
        set -eu
        umask 077
        # komichi_job___JOB__
        printf 'KOMICHI_PID __JOB__ %s\n' "§§"
        fail() { printf 'KOMICHI_ERROR %s\n' "§1" >&2; exit 20; }
        lease=__LEASE__
        [ -f "§lease" ] && [ "§(/system/bin/cat "§lease")" = '__JOB__' ] || fail CANCELLED
        [ "§(/system/bin/id -u)" = 0 ] || fail ROOT_REQUIRED
        source_base=__SOURCE_BASE__
        source_request=__SOURCE_REQUEST__
        [ -d "§source_base" ] || fail SOURCE_ROOT_UNAVAILABLE
        real_root=§(/system/bin/readlink -f "§source_base") || fail SOURCE_ROOT_UNAVAILABLE
        case "§real_root" in __ROOT_CASE__) ;; *) fail SOURCE_SCOPE ;; esac
        real_source=§(/system/bin/readlink -f "§source_request") || fail SOURCE_UNAVAILABLE
        case "§real_source" in "§real_root"|"§real_root"/*) ;; *) fail SOURCE_SCOPE ;; esac
        [ -d "§real_source" ] || [ -f "§real_source" ] || fail SOURCE_UNAVAILABLE
        exec 3< "§real_source"
        source_handle="/proc/§§/fd/3"
        opened_source=§(/system/bin/readlink -f "§source_handle") || fail SOURCE_UNAVAILABLE
        case "§opened_source" in "§real_root"|"§real_root"/*) ;; *) fail SOURCE_SCOPE ;; esac
    """
    private fun sourceValues(source: AppDataSource, jobId: String, leasePath: String): Map<String,String> {
        require(jobId.matches(Regex("[a-f0-9]{32}")))
        return mapOf("__JOB__" to jobId,"__LEASE__" to quote(leasePath),"__SOURCE_BASE__" to quote(source.root),"__SOURCE_REQUEST__" to quote(source.path),
            "__ROOT_CASE__" to source.canonicalRoots.joinToString("|") { quote(it) })
    }
    fun list(source: AppDataSource, jobId: String, leasePath: String): String = render(SOURCE + """

        [ -d "§source_handle" ] || fail NOT_DIRECTORY
        count=0
        printf 'KOMICHI_LIST\n'
        for item in "§source_handle"/* "§source_handle"/.[!.]* "§source_handle"/..?*; do
            [ -e "§item" ] || [ -L "§item" ] || continue
            count=§((count + 1))
            [ "§count" -le 60 ] || break
            kind=file
            if [ -L "§item" ]; then kind=symlink; elif [ -d "§item" ]; then kind=directory; fi
            name=§{item##*/}
            printf '%s\000%s\000' "§kind" "§name"
        done
        printf '\nKOMICHI_OK __JOB__ listed\n'
    """,sourceValues(source,jobId,leasePath))

    fun copy(plan: AppDataCopyPlan, leasePath: String): String = render(SOURCE + """

        destination_root=__DESTINATION_ROOT__
        parent_relative=__PARENT__
        name=__NAME__
        format=__FORMAT__
        marker='.komichi-__JOB__.incomplete'
        child=''
        abort_copy() { if [ -n "§child" ]; then kill -TERM "§child" 2>/dev/null || :; fi; exit 130; }
        trap abort_copy HUP INT TERM
        wait_copy() {
            remaining=180
            while kill -0 "§child" 2>/dev/null; do
                [ -f "§lease" ] || abort_copy
                [ "§remaining" -gt 0 ] || abort_copy
                remaining=§((remaining - 1))
                /system/bin/sleep 1
            done
            if wait "§child"; then :; else fail COPY_FAILED; fi
            child=''
        }
        root_destination=§(/system/bin/readlink -f "§destination_root") || fail DESTINATION_UNAVAILABLE
        [ "§root_destination" = "§destination_root" ] || fail DESTINATION_SCOPE
        cd "§root_destination" || fail DESTINATION_UNAVAILABLE
        set -f
        IFS=/
        for part in §parent_relative; do
            [ ! -L "./§part" ] || fail DESTINATION_SYMLINK
            if [ ! -d "./§part" ]; then /system/bin/mkdir "./§part" || fail DESTINATION_UNWRITABLE; fi
            cd "./§part" || fail DESTINATION_UNAVAILABLE
            current=§(pwd -P)
            case "§current" in "§root_destination"|"§root_destination"/*) ;; *) fail DESTINATION_SCOPE ;; esac
        done
        unset IFS
        set +f
        [ ! -e "./§name" ] && [ ! -L "./§name" ] || fail DESTINATION_EXISTS
        [ -f "§lease" ] || fail CANCELLED
        if [ "§format" = directory ] && [ -d "§source_handle" ]; then
            /system/bin/mkdir "./§name" || fail DESTINATION_EXISTS
            expected="§(pwd -P)/§name"
            cd "./§name" || fail DESTINATION_UNAVAILABLE
            [ "§(pwd -P)" = "§expected" ] || fail DESTINATION_SCOPE
            printf 'Incomplete Komichi copy; source was not modified.\n' > "./§marker"
            /system/bin/cp -R -P "§source_handle/." . &
            child=§!
            wait_copy
            /system/bin/rm "./§marker"
            printf 'KOMICHI_OK __JOB__ directory\n'
        else
            set -C
            exec 5> "./§name"
            set +C
            destination_handle="/proc/§§/fd/5"
            printf 'Incomplete Komichi copy; source was not modified.\n' > "./§marker"
            if [ "§format" = tar ]; then
                if [ -d "§source_handle" ]; then
                    /system/bin/tar -cf "§destination_handle" -C "§source_handle" . &
                else
                    source_parent=§{real_source%/*}
                    source_name=§{real_source##*/}
                    exec 4< "§source_parent"
                    parent_handle="/proc/§§/fd/4"
                    opened_parent=§(/system/bin/readlink -f "§parent_handle") || fail SOURCE_UNAVAILABLE
                    case "§opened_parent" in "§real_root"|"§real_root"/*) ;; *) fail SOURCE_SCOPE ;; esac
                    /system/bin/tar -cf "§destination_handle" -C "§parent_handle" "./§source_name" &
                fi
            else
                /system/bin/cat "§source_handle" >&5 &
            fi
            child=§!
            wait_copy
            [ "./§name" -ef "§destination_handle" ] || fail VERIFY_FAILED
            /system/bin/rm "./§marker"
            printf 'KOMICHI_OK __JOB__ %s\n' "§format"
        fi
    """,sourceValues(plan.source,plan.jobId,leasePath) + mapOf("__DESTINATION_ROOT__" to quote(plan.destination.storageRoot),
        "__PARENT__" to quote(plan.destination.parent),"__NAME__" to quote(plan.destination.name),"__FORMAT__" to quote(plan.format)))

    fun cancel(pid: Int, jobId: String): String {
        require(pid > 1 && jobId.matches(Regex("[a-f0-9]{32}")))
        return "case \"$(/system/bin/cat /proc/$pid/cmdline 2>/dev/null)\" in *'komichi_job_$jobId'*) kill -TERM $pid ;; esac"
    }
}
