import java.nio.file.*;

public class PatchService {
    public static void main(String[] args) throws Exception {
        String file = "d:/pythonProject/niren-drama/backend/src/main/java/com/niren/drama/service/StoryboardService.java";
        String content = new String(Files.readAllBytes(Paths.get(file)), "UTF-8");
        
        String head = "    public void streamGenerateStoryboard(";
        String tail = "        projectService.getProject(userId, request.getProjectId());";
        
        System.out.println("Length: " + content.length());
    }
}
