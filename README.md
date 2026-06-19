---

## System Design & Execution Flow

This shell is built around a classic REPL loop:

```text
Read command → Parse tokens → Expand variables → Detect builtins/external commands → Execute → Print result → Show next prompt
```

---

## 1. High-Level Shell Architecture

```mermaid
flowchart TD
    A[User Input] --> B[Interactive REPL]
    B --> C[Command Parser]

    C --> D[Variable Expansion]
    D --> E{Command Type}

    E -->|Builtin| F[Builtin Executor]
    E -->|External Command| G[ProcessBuilder Executor]
    E -->|Pipeline| H[Pipeline Engine]
    E -->|Background Job| I[Job Manager]

    F --> J[Output / Redirection Handler]
    G --> J
    H --> J
    I --> K[Jobs Table]

    J --> L[Next Prompt]
    K --> L
```

The shell keeps track of:

* current working directory
* built-in commands
* shell variables
* command history
* background jobs
* programmable completion rules

---

## 2. Autocompletion Engine

The autocomplete engine handles `<TAB>` keypresses and chooses the correct completion strategy depending on the user input.

```mermaid
sequenceDiagram
    participant User
    participant Shell
    participant Completer as Completer Script
    participant FS as File System

    User->>Shell: Presses TAB
    Shell->>Shell: Parse current input line

    alt Programmable completer registered
        Shell->>Completer: Execute script with argv + COMP_LINE / COMP_POINT
        Completer-->>Shell: Return candidates
    else Completing command name
        Shell->>Shell: Match builtins
        Shell->>FS: Scan PATH directories
        FS-->>Shell: Return executable matches
    else Completing file or directory
        Shell->>FS: Scan target directory
        FS-->>Shell: Return file/folder matches
    end

    alt Exactly one match
        Shell-->>User: Complete token + trailing space or slash
    else Multiple matches
        Shell-->>User: First TAB rings bell
        Shell-->>User: Second TAB lists all candidates
    else No match
        Shell-->>User: Ring bell
    end
```

Completion supports:

* built-in command completion
* executable completion from `PATH`
* file completion
* nested path completion
* directory completion
* multiple match listing
* longest common prefix completion
* programmable completion using `complete -C`
* completion in any argument position

Example:

```bash
$ ech<TAB>
$ echo 

$ cat rea<TAB>
$ cat readme.txt 

$ cd proj<TAB>
$ cd project/
```

---

## 3. Pipeline Execution Flow

The shell supports both built-in pipelines and streaming external pipelines.

```mermaid
flowchart LR
    A[Command Line] --> B[Parse Pipeline]
    B --> C{All commands external?}

    C -->|Yes| D[Use ProcessBuilder.startPipeline]
    D --> E[Stream output between processes]
    E --> F[Wait for last process]

    C -->|No| G[Run pipeline step-by-step]
    G --> H[Capture output as bytes]
    H --> I[Pass output to next command]

    F --> J[Print final output]
    I --> J
```

Examples:

```bash
echo hello | wc
cat file.txt | head -n 3 | wc
tail -f file.txt | head -n 5
```

For external commands like `tail -f file | head -n 5`, the shell uses streaming pipeline execution so long-running commands do not block the entire shell.

---

## 4. Background Job Management

Background jobs are tracked in an internal jobs table.

```mermaid
stateDiagram-v2
    [*] --> Started
    Started --> Running: command &
    Running --> Done: process exits
    Done --> Reaped: shell prints Done message
    Reaped --> [*]
```

Example:

```bash
$ sleep 10 &
[1] 12345

$ jobs
[1]+  Running                 sleep 10 &

$ echo done
done
[1]+  Done                    sleep 10
```

The job system supports:

* job numbers
* running job display
* completed job reaping
* job number recycling
* current job marker `+`
* previous job marker `-`

---

## 5. History System

The shell records every executed command in memory and supports file-based history.

```mermaid
flowchart TD
    A[Command Executed] --> B[Add to In-Memory History]
    B --> C{History Command?}

    C -->|history| D[Print history]
    C -->|history n| E[Print last n commands]
    C -->|history -r file| F[Read file into memory]
    C -->|history -w file| G[Write memory to file]
    C -->|history -a file| H[Append new entries]

    B --> I{Exit with HISTFILE?}
    I -->|Yes| J[Append session history to HISTFILE]
    I -->|No| K[Exit normally]
```

Supported commands:

```bash
history
history 5
history -r history.txt
history -w history.txt
history -a history.txt
```

Arrow-key history navigation is also supported:

```text
UP arrow   → previous command
DOWN arrow → next command
ENTER      → execute recalled command
```

---

## 6. Shell Variable Expansion

The shell supports variable declaration and expansion.

```mermaid
flowchart LR
    A[declare name=value] --> B[Store variable]
    B --> C[Command contains $name or ${name}]
    C --> D[Expand variable before execution]
    D --> E[Run builtin or external command]
```

Examples:

```bash
$ declare name=Sanskar
$ echo $name
Sanskar

$ declare Item=widget
$ echo stock_${Item}_id
stock_widget_id
```

Supported variable features:

* `declare name=value`
* `declare -p name`
* valid identifier checking
* `$VAR` expansion
* `${VAR}` expansion
* unset variables expanding to empty string

---

## 7. Redirection Flow

```mermaid
flowchart TD
    A[Command Input] --> B[Parse Redirection Tokens]
    B --> C{Redirection Type}

    C -->|>| D[Write stdout to file]
    C -->|>>| E[Append stdout to file]
    C -->|2>| F[Write stderr to file]
    C -->|2>>| G[Append stderr to file]

    D --> H[Execute Command]
    E --> H
    F --> H
    G --> H
```

Examples:

```bash
echo hello > output.txt
echo world >> output.txt
invalid_command 2> error.txt
invalid_command 2>> error.txt
```

---

## 8. Builtin Command Table

| Builtin    | Purpose                                       |
| ---------- | --------------------------------------------- |
| `echo`     | Print arguments                               |
| `exit`     | Exit shell                                    |
| `type`     | Show whether a command is builtin or external |
| `pwd`      | Print current directory                       |
| `cd`       | Change directory                              |
| `jobs`     | List background jobs                          |
| `complete` | Register programmable completions             |
| `history`  | Show and manage command history               |
| `declare`  | Store and inspect shell variables             |

---

## 9. Internal Flow Summary

```mermaid
flowchart TD
    A[Start Shell] --> B[Load HISTFILE]
    B --> C[Show Prompt]
    C --> D[Read Character Input]

    D -->|Normal characters| E[Update input buffer]
    D -->|TAB| F[Autocomplete Engine]
    D -->|Arrow keys| G[History Navigation]
    D -->|ENTER| H[Execute Command]

    H --> I[Parse Command]
    I --> J[Expand Variables]
    J --> K{Builtin / External / Pipeline / Background}

    K --> L[Run Builtin]
    K --> M[Run External Process]
    K --> N[Run Pipeline]
    K --> O[Start Background Job]

    L --> P[Reap Finished Jobs]
    M --> P
    N --> P
    O --> P

    P --> C
```

---
