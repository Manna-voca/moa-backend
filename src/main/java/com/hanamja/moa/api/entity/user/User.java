package com.hanamja.moa.api.entity.user;

import com.hanamja.moa.api.entity.album.Album;
import com.hanamja.moa.api.entity.department.Department;
import com.hanamja.moa.api.entity.group.Group;
import com.hanamja.moa.api.entity.point_history.PointHistory;
import com.hanamja.moa.api.entity.user_group.UserGroup;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Year;
import java.util.List;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "MOA_USER")
public class User {

    private static final String FRESHMAN_YEAR = String.valueOf(Year.now().getValue());

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "student_id", nullable = false, unique = true)
    private String studentId;

    @Column(name = "pwd", nullable = false)
    private String password;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "gender")
    @Enumerated(EnumType.STRING)
    private Gender gender;

    @Column(name = "image_link")
    private String imageLink;

    @Column(name = "point")
    private Long point;

    @Column(name = "intro")
    private String intro;

    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dept_id")
    private Department department;

    @OneToMany(mappedBy = "owner")
    private List<Album> albumList;

    @OneToMany(mappedBy = "joiner")
    private List<UserGroup> userGroupList;

    @OneToMany(mappedBy = "maker")
    private List<Group> groupList;

//    @OneToMany(mappedBy = "metUser")
//    private List<Album> metAlbumList;

    @Column(name = "is_onboarded", nullable = false)
    private Boolean isOnboarded;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "is_notified", nullable = false)
    private Boolean isNotified;


    @OneToMany(mappedBy = "owner")
    private List<PointHistory> pointHistoryList;

    @Builder
    public User(String studentId, String password, String name, Gender gender, String imageLink, Long point, String intro, Department department) {
        this.studentId = studentId;
        this.password = password;
        this.name = name;
        this.gender = gender;
        this.imageLink = imageLink;
        this.point = 0L;
        this.intro = intro;
        this.role = studentId.startsWith(FRESHMAN_YEAR) ? Role.ROLE_FRESHMEN : Role.ROLE_SENIOR;
        this.department = department;
        this.isOnboarded = false;
        this.isActive = false;
        this.isNotified = false;
    }

    public boolean isFreshman() {
        String year = Integer.toString(Year.now().getValue());
        return this.studentId.startsWith(year);
    }

    // ???????????? ???????????? ??????
    public void updateOnBoardingInfo(Gender gender, Department department, String imageLink) {
        this.gender = gender;
        this.department = department;
        this.imageLink = imageLink == null ? this.imageLink : imageLink;
        this.isOnboarded = true;
    }

    // ??????????????? ????????? ???????????? ??????
    public void modifyUserInfo(Gender gender, Department department, String intro, String imageLink) {
        this.gender = gender;
        this.department = department;
        this.intro = intro;
        this.imageLink = imageLink == null ? this.imageLink : imageLink;
    }

    public void updateProfileImage(String profileImageUrl) {
        this.imageLink = profileImageUrl;
    }

    public void notifyUser() {
        this.isNotified = true;
    }

    public void unNotifyUser() {
        this.isNotified = false;
    }

    public void addPoint(Long point) {
        this.point += point;
    }
}
