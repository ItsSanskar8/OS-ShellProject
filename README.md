[![progress-banner](https://backend.codecrafters.io/progress/shell/c6ae9fd8-3402-4249-9131-6fb68122d4d9)](https://app.codecrafters.io/users/ItsSanskar8?r=2qF)

# Java Shell — CodeCrafters Build Your Own Shell

A POSIX-inspired shell implementation built in **Java** for the [CodeCrafters Build Your Own Shell](https://codecrafters.io) challenge.

This project implements a custom command-line shell capable of parsing user input, running external programs, handling built-in commands, supporting pipelines, redirections, tab completion, background jobs, command history, programmable completion, and shell variable expansion.

---

## Features

### Core Shell Features

* Interactive REPL with `$` prompt
* External command execution using `PATH`
* Built-in command support
* Command parsing with:

  * single quotes
  * double quotes
  * escape characters
  * whitespace handling
* Absolute and relative path support

### Built-in Commands

Supported built-ins include:

* `echo`
* `exit`
* `type`
* `pwd`
* `cd`
* `jobs`
* `complete`
* `history`
* `declare`

### Redirection

Supports stdout and stderr redirection:

```bash
echo hello > file.txt
echo hello 1> file.txt
echo hello >> file.txt
invalid_command 2> error.txt
invalid_command 2>> error.txt
```

### Pipelines

Supports single and multi-command pipelines:

```bash
echo hello | wc
cat file.txt | head -n 3 | wc
tail -f file.txt | head -n 5
```

The implementation supports streaming pipelines for external commands, including long-running commands such as `tail -f`.

### Background Jobs

Supports running commands in the background:

```bash
sleep 10 &
jobs
```

Job handling includes:

* job numbers
* running job listing
* completed job reaping
* job number recycling
* shell-style job markers: `+`, `-`, and blank marker

### Tab Completion

Supports command and argument completion using the `<TAB>` key.

Implemented completion features:

* built-in command completion
* executable completion from `PATH`
* filename completion
* nested path completion
* directory completion with trailing `/`
* multiple match listing
* longest common prefix completion
* completion in any argument position
* bell character on missing completion

Example:

```bash
cat rea<TAB>
cd proj<TAB>
ls r<TAB><TAB>
```

### Programmable Completion

Supports the `complete` builtin:

```bash
complete -C /path/to/completer git
complete -p git
complete -r git
```

Implemented programmable completion features:

* registering completer scripts with `complete -C`
* printing registered completions with `complete -p`
* removing completions with `complete -r`
* invoking completer scripts on `<TAB>`
* passing completion arguments:

  * command name
  * current word
  * previous word
* setting completion environment variables:

  * `COMP_LINE`
  * `COMP_POINT`
* handling multiple candidates
* longest common prefix completion

### Command History

Supports the `history` builtin:

```bash
history
history 5
history -r history.txt
history -w history.txt
history -a history.txt
```

Implemented history features:

* in-memory command history
* limiting output with `history <n>`
* recalling commands with arrow keys
* executing recalled commands
* reading history from file
* writing history to file
* appending new history entries
* `HISTFILE` support on startup and exit

### Shell Variables

Supports the `declare` builtin:

```bash
declare name=value
declare -p name
```

Implemented variable features:

* variable declaration
* variable inspection
* identifier validation
* `$VAR` expansion
* `${VAR}` expansion
* unset variables expanding to an empty string

Example:

```bash
declare user=Sanskar
echo Hello $user
echo ${user}_project
```

---

## Tech Stack

* Java
* Maven
* Unix process APIs
* CodeCrafters CLI

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

## Getting Started

### Prerequisites

Make sure you have:

* Java installed
* Maven installed
* CodeCrafters CLI installed

Check versions:

```bash
java --version
mvn --version
codecrafters --version
```

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

You should see:

```bash
$
```

Now you can run commands:

```bash
echo hello
pwd
type echo
history
exit
```

---

## Testing

Submit to CodeCrafters:

```bash
codecrafters submit
```

The CodeCrafters test runner will compile the project and run the current challenge stage tests.

---

## Example Usage

```bash
$ echo hello
hello

$ pwd
/Users/example/codecrafters-shell-java

$ type echo
echo is a shell builtin

$ echo hello | wc
       1       1       6

$ sleep 10 &
[1] 12345

$ jobs
[1]+  Running                 sleep 10 &

$ declare name=Sanskar

$ echo $name
Sanskar

$ history
    1  echo hello
    2  pwd
    3  type echo
    4  echo hello | wc
    5  sleep 10 &
    6  jobs
    7  declare name=Sanskar
    8  echo $name
    9  history
```

---

## Notes

This project is built as part of the CodeCrafters shell challenge. It is a learning-focused shell implementation and is POSIX-inspired, not a full production replacement for shells like Bash, Zsh, or Fish.

The goal of this project is to understand how shells work internally, including command parsing, process execution, pipes, redirection, job control, command history, tab completion, and shell variables.

---

## Author

Built by **Sanskar Bhanderi** as part of the CodeCrafters Build Your Own Shell challenge.
