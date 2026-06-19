# Java Shell — Build Your Own Shell

A POSIX-inspired shell implemented in **Java** as part of the CodeCrafters **Build Your Own Shell** challenge.

This project builds a custom command-line shell from scratch. It supports command parsing, built-in commands, external programs, redirection, pipelines, background jobs, tab completion, programmable completion, command history, and shell variables.

---

## Project Overview

A shell is a command interpreter. It reads user input, understands the command, runs the required program, and prints the output.

This project implements that flow manually using Java.

```text
User types command
        ↓
Shell reads input
        ↓
Parser converts input into tokens
        ↓
Variables are expanded
        ↓
Shell decides: builtin, external command, pipeline, or background job
        ↓
Command executes
        ↓
Output is printed or redirected
        ↓
Prompt returns
```

---

## Features

### Core Shell

* Interactive REPL with `$` prompt
* External command execution using `PATH`
* Built-in commands
* Command parsing with:

  * spaces
  * quotes
  * escape characters
  * pipes
  * redirection operators
* Relative and absolute path support

### Built-in Commands

| Command    | Description                                    |
| ---------- | ---------------------------------------------- |
| `echo`     | Prints text                                    |
| `exit`     | Exits the shell                                |
| `type`     | Shows whether a command is builtin or external |
| `pwd`      | Prints current working directory               |
| `cd`       | Changes directory                              |
| `jobs`     | Lists background jobs                          |
| `complete` | Registers programmable completion              |
| `history`  | Shows and manages command history              |
| `declare`  | Stores and inspects shell variables            |

---

## System Architecture

```mermaid
flowchart TD
    A["User Input"] --> B["REPL Loop"]
    B --> C["Command Parser"]
    C --> D["Variable Expansion"]
    D --> E{"Command Type"}

    E --> F["Builtin Command"]
    E --> G["External Program"]
    E --> H["Pipeline"]
    E --> I["Background Job"]

    F --> J["Output Handler"]
    G --> J
    H --> J
    I --> K["Job Table"]

    J --> L["Next Prompt"]
    K --> L
```

---

## Command Execution Flow

```mermaid
sequenceDiagram
    participant User
    participant Shell
    participant Parser
    participant Executor
    participant OS

    User->>Shell: Type command
    Shell->>Parser: Parse input
    Parser-->>Shell: Return tokens
    Shell->>Shell: Expand variables
    Shell->>Executor: Choose execution strategy
    Executor->>OS: Start process or run builtin
    OS-->>Executor: Return output
    Executor-->>Shell: Send result
    Shell-->>User: Print output and prompt
```

---

## Autocompletion Engine

The autocomplete system reacts when the user presses the `<TAB>` key.

It supports:

* builtin completion
* executable completion from `PATH`
* file completion
* directory completion
* nested path completion
* multiple match listing
* longest common prefix completion
* programmable completion using `complete -C`

```mermaid
sequenceDiagram
    participant User
    participant Shell
    participant Script as Completer Script
    participant FS as File System

    User->>Shell: Press TAB
    Shell->>Shell: Detect cursor context

    alt Programmable completer exists
        Shell->>Script: Run completer with args and environment
        Script-->>Shell: Return candidates
    else Completing command name
        Shell->>Shell: Match builtins
        Shell->>FS: Scan PATH
        FS-->>Shell: Return executable matches
    else Completing file or directory
        Shell->>FS: Scan target directory
        FS-->>Shell: Return file and directory matches
    end

    alt One match
        Shell-->>User: Complete text
    else Multiple matches
        Shell-->>User: First TAB rings bell
        Shell-->>User: Second TAB shows candidates
    else No match
        Shell-->>User: Ring bell
    end
```

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

## Pipeline Engine

The shell supports normal and streaming pipelines.

```mermaid
flowchart LR
    A["Input command"] --> B["Split by pipe"]
    B --> C{"All commands external?"}

    C --> D["Use streaming pipeline"]
    D --> E["Connect process output to next process"]
    E --> F["Wait for final command"]

    C --> G["Run builtin-aware pipeline"]
    G --> H["Capture output"]
    H --> I["Pass output to next command"]

    F --> J["Print final output"]
    I --> J
```

Examples:

```bash
echo hello | wc
cat file.txt | head -n 3 | wc
tail -f file.txt | head -n 5
```

The shell uses streaming execution for long-running external pipelines such as:

```bash
tail -f file.txt | head -n 5
```

---

## Background Job System

Commands ending with `&` run in the background.

```mermaid
stateDiagram-v2
    [*] --> Started
    Started --> Running: command with ampersand
    Running --> Done: process exits
    Done --> Reaped: shell prints done message
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

Supported job features:

* background execution
* job numbers
* job number recycling
* `jobs` builtin
* running job display
* completed job reaping
* current job marker `+`
* previous job marker `-`

---

## History System

The shell stores previously executed commands.

```mermaid
flowchart TD
    A["Command executed"] --> B["Add command to memory"]
    B --> C{"History command?"}

    C --> D["history"]
    C --> E["history n"]
    C --> F["history -r file"]
    C --> G["history -w file"]
    C --> H["history -a file"]

    D --> I["Print full history"]
    E --> J["Print last n commands"]
    F --> K["Read file into memory"]
    G --> L["Write memory to file"]
    H --> M["Append new entries"]
```

Supported history features:

```bash
history
history 5
history -r history.txt
history -w history.txt
history -a history.txt
```

Arrow key navigation:

```text
UP arrow     previous command
DOWN arrow   next command
ENTER        execute recalled command
```

The shell also supports the `HISTFILE` environment variable.

---

## Shell Variables

The shell supports variable declaration and expansion using `declare`.

```mermaid
flowchart LR
    A["declare name=value"] --> B["Store variable"]
    B --> C["Command uses variable reference"]
    C --> D["Expand variable"]
    D --> E["Execute command"]
```

Examples:

```bash
$ declare name=Sanskar
$ echo $name
Sanskar

$ declare item=widget
$ echo stock_${item}_id
stock_widget_id
```

Supported variable features:

* `declare name=value`
* `declare -p name`
* identifier validation
* simple variable expansion
* braced variable expansion
* unset variables expand to empty string

---

## Redirection

The shell supports stdout and stderr redirection.

```mermaid
flowchart TD
    A["Command input"] --> B["Parse redirection"]
    B --> C{"Redirection type"}

    C --> D["Write stdout"]
    C --> E["Append stdout"]
    C --> F["Write stderr"]
    C --> G["Append stderr"]

    D --> H["Run command"]
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

## Programmable Completion

The `complete` builtin allows registering external completer scripts.

```bash
complete -C /path/to/completer git
complete -p git
complete -r git
```

The shell passes completion context to the script:

```text
argv[1] = command name
argv[2] = current word
argv[3] = previous word
COMP_LINE = full command line
COMP_POINT = cursor position
```

---

## Project Structure

```text
.
├── .codecrafters/
│   └── run.sh
├── src/
│   └── main/
│       └── java/
│           └── Main.java
├── pom.xml
└── README.md
```

---

## Tech Stack

* Java
* Maven
* Unix process APIs
* CodeCrafters CLI
* Mermaid diagrams for documentation

---

## Run Locally

Compile the project:

```bash
mvn clean compile
```

Run the shell:

```bash
.codecrafters/run.sh
```

Example session:

```bash
$ echo hello
hello

$ pwd
/Users/example/codecrafters-shell-java

$ type echo
echo is a shell builtin

$ echo hello | wc
       1       1       6

$ history
    1  echo hello
    2  pwd
    3  type echo
    4  echo hello | wc
    5  history
```

---

## Run CodeCrafters Tests

```bash
codecrafters submit
```

---

## Learning Outcomes

This project helped build practical understanding of:

* how shells parse commands
* how builtins work
* how external programs are executed
* how pipes connect processes
* how redirection works
* how background jobs are tracked
* how tab completion works
* how command history is stored
* how shell variables are expanded

---

## Note

This is a learning-focused shell implementation. It is POSIX-inspired, but it is not intended to replace production shells like Bash, Zsh, or Fish.

---

## Author

Built by **Sanskar Bhanderi** as part of the CodeCrafters Build Your Own Shell challenge.
