with open("app/src/main/java/com/example/ui/MainViewModel.kt", "r") as f:
    content = f.read()

new_func = """    fun importAndPlay4ReadHtml(url: String, html: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val importedBook = repository.importAudiobookFromHtml(url, html)
            viewModelScope.launch(Dispatchers.Main) {
                playAudiobook(importedBook, chapterIndex = 0, autoPlay = true)
                _showFullPlayer.value = true
            }
        }
    }
"""
content = content.replace("    fun importAndPlay4ReadUrl", new_func + "\n    fun importAndPlay4ReadUrl")

with open("app/src/main/java/com/example/ui/MainViewModel.kt", "w") as f:
    f.write(content)
