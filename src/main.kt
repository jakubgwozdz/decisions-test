fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "prepare" -> prepare.run(args.drop(1).toTypedArray())
        "generate" -> generate.run(args.drop(1).toTypedArray())
        else -> {
            println("Usage: <prepare|generate|compare> [args...]")
            kotlin.system.exitProcess(1)
        }
    }
}
